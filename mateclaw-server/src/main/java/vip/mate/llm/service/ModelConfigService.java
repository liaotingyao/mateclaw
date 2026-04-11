package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import java.util.List;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 模型配置服务
 */
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelConfigMapper modelConfigMapper;
    private final ApplicationEventPublisher eventPublisher;

    public List<ModelConfigEntity> listModels() {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .orderByDesc(ModelConfigEntity::getIsDefault)
                .orderByAsc(ModelConfigEntity::getProvider)
                .orderByAsc(ModelConfigEntity::getName));
    }

    public List<ModelConfigEntity> listEnabledModels() {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getEnabled, true)
                .eq(ModelConfigEntity::getProvider, "dashscope")
                .orderByDesc(ModelConfigEntity::getIsDefault)
                .orderByAsc(ModelConfigEntity::getName));
    }

    public List<ModelConfigEntity> listModelsByProvider(String providerId) {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .orderByDesc(ModelConfigEntity::getBuiltin)
                .orderByAsc(ModelConfigEntity::getName));
    }

    public ModelConfigEntity getModel(Long id) {
        ModelConfigEntity entity = modelConfigMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("err.llm.model_config_not_found", "模型配置不存在: " + id);
        }
        return entity;
    }

    public ModelConfigEntity getDefaultModel() {
        ModelConfigEntity entity = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getIsDefault, true)
                .last("LIMIT 1"));
        if (entity != null) {
            return entity;
        }
        entity = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getEnabled, true)
                .orderByAsc(ModelConfigEntity::getName)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new MateClawException("err.llm.no_available_model", "没有可用的模型配置");
        }
        return entity;
    }

    public ModelConfigEntity getDefaultModelByProvider(String providerId) {
        return modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getIsDefault, true)
                .last("LIMIT 1"));
    }

    public ModelConfigEntity createModel(ModelConfigEntity entity) {
        validateModel(entity, null);
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            clearDefaultFlag();
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        if (entity.getBuiltin() == null) {
            entity.setBuiltin(true);
        }
        if (entity.getIsDefault() == null) {
            entity.setIsDefault(false);
        }
        modelConfigMapper.insert(entity);
        ensureDefaultExists();
        publishConfigChanged("model-created");
        return entity;
    }

    public ModelConfigEntity updateModel(ModelConfigEntity entity) {
        ModelConfigEntity existing = getModel(entity.getId());
        validateModel(entity, existing.getId());
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            clearDefaultFlag();
        }
        if (existing.getIsDefault() && Boolean.FALSE.equals(entity.getEnabled())) {
            throw new MateClawException("err.llm.cannot_disable_default", "默认模型不能被禁用，请先切换默认模型");
        }
        modelConfigMapper.updateById(entity);
        ensureDefaultExists();
        publishConfigChanged("model-updated");
        return getModel(entity.getId());
    }

    public void deleteModel(Long id) {
        ModelConfigEntity entity = getModel(id);
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            throw new MateClawException("err.llm.cannot_delete_default", "默认模型不能删除，请先切换默认模型");
        }
        modelConfigMapper.deleteById(id);
        ensureDefaultExists();
        publishConfigChanged("model-deleted");
    }

    public ModelConfigEntity addModelToProvider(String providerId, String modelId, String displayName, boolean builtin) {
        if (!StringUtils.hasText(providerId) || !StringUtils.hasText(modelId)) {
            throw new MateClawException("err.llm.provider_model_required", "Provider 和模型标识不能为空");
        }
        ModelConfigEntity existing = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getModelName, modelId)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new MateClawException("err.llm.model_exists", "模型已存在: " + modelId);
        }
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setName(StringUtils.hasText(displayName) ? displayName : modelId);
        entity.setProvider(providerId);
        entity.setModelName(modelId);
        entity.setDescription("");
        entity.setTemperature(0.7);
        entity.setMaxTokens(4096);
        entity.setTopP(0.8);
        entity.setBuiltin(builtin);
        entity.setEnabled(true);
        entity.setIsDefault(false);
        modelConfigMapper.insert(entity);
        ensureDefaultExists();
        publishConfigChanged("provider-model-added");
        return entity;
    }

    public void removeModelFromProvider(String providerId, String modelId) {
        ModelConfigEntity entity = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getModelName, modelId)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new MateClawException("err.llm.model_not_found", "模型不存在: " + modelId);
        }
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw new MateClawException("err.llm.builtin_readonly", "内置模型不支持删除");
        }
        deleteModel(entity.getId());
    }

    public void deleteModelsByProvider(String providerId) {
        List<ModelConfigEntity> entities = listModelsByProvider(providerId);
        for (ModelConfigEntity entity : entities) {
            modelConfigMapper.deleteById(entity.getId());
        }
        ensureDefaultExists();
        publishConfigChanged("provider-models-deleted");
    }

    public ModelConfigEntity setDefaultModel(Long id) {
        ModelConfigEntity entity = getModel(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new MateClawException("err.llm.only_enabled_default", "只有启用状态的模型才能设为默认");
        }
        clearDefaultFlag();
        entity.setIsDefault(true);
        modelConfigMapper.updateById(entity);
        publishConfigChanged("default-model-updated");
        return entity;
    }

    public ModelConfigEntity setDefaultModel(String providerId, String modelName) {
        ModelConfigEntity entity = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getModelName, modelName)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new MateClawException("err.llm.model_not_found", "模型不存在: " + providerId + "/" + modelName);
        }
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            // Auto-enable when setting as default (e.g. local Ollama models)
            entity.setEnabled(true);
        }
        clearDefaultFlag();
        entity.setIsDefault(true);
        modelConfigMapper.updateById(entity);
        publishConfigChanged("default-model-updated");
        return entity;
    }

    public ModelConfigEntity resolveModel(String agentModelName) {
        if (StringUtils.hasText(agentModelName)) {
            ModelConfigEntity entity = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                    .eq(ModelConfigEntity::getModelName, agentModelName)
                    .eq(ModelConfigEntity::getEnabled, true)
                    .last("LIMIT 1"));
            if (entity != null) {
                return entity;
            }
        }
        return getDefaultModel();
    }

    private void validateModel(ModelConfigEntity entity, Long currentId) {
        if (!StringUtils.hasText(entity.getName())) {
            throw new MateClawException("err.llm.name_required", "模型名称不能为空");
        }
        if (!StringUtils.hasText(entity.getProvider())) {
            entity.setProvider("dashscope");
        }
        if (!StringUtils.hasText(entity.getModelName())) {
            throw new MateClawException("err.llm.id_required", "模型标识不能为空");
        }
        ModelConfigEntity duplicate = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, entity.getProvider())
                .eq(ModelConfigEntity::getModelName, entity.getModelName())
                .ne(currentId != null, ModelConfigEntity::getId, currentId)
                .last("LIMIT 1"));
        if (duplicate != null) {
            throw new MateClawException("err.llm.id_exists", "模型标识已存在: " + entity.getProvider() + "/" + entity.getModelName());
        }
    }

    private void clearDefaultFlag() {
        List<ModelConfigEntity> defaults = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getIsDefault, true));
        for (ModelConfigEntity item : defaults) {
            item.setIsDefault(false);
            modelConfigMapper.updateById(item);
        }
    }

    private void ensureDefaultExists() {
        long defaultCount = modelConfigMapper.selectCount(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getIsDefault, true)
                .eq(ModelConfigEntity::getEnabled, true));
        if (defaultCount > 0) {
            return;
        }
        ModelConfigEntity firstEnabled = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getEnabled, true)
                .orderByAsc(ModelConfigEntity::getName)
                .last("LIMIT 1"));
        if (firstEnabled != null) {
            firstEnabled.setIsDefault(true);
            modelConfigMapper.updateById(firstEnabled);
        }
    }

    private void publishConfigChanged(String reason) {
        eventPublisher.publishEvent(new ModelConfigChangedEvent(reason));
    }
}
