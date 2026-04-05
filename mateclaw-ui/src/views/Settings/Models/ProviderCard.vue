<template>
  <div>
    <div class="provider-header">
      <div>
        <div class="provider-title-row">
          <img
            :src="getProviderIcon(provider.id)"
            :alt="provider.name"
            class="provider-icon"
            @error="onIconError"
          />
          <h3 class="provider-name">{{ provider.name }}</h3>
          <span class="provider-badge" :class="provider.isCustom ? 'custom' : 'builtin'">
            {{ provider.isCustom ? t('settings.model.custom') : t('settings.model.builtin') }}
          </span>
          <span v-if="isProviderActive(provider)" class="provider-badge active">
            {{ t('settings.model.active') }}
          </span>
        </div>
        <p class="provider-id">{{ provider.id }}</p>
      </div>
      <div class="provider-status" :class="providerStatus(provider).type">
        {{ providerStatus(provider).label }}
      </div>
    </div>

    <div class="provider-info">
      <div class="info-row">
        <span class="info-label">{{ t('settings.model.baseUrl') }}</span>
        <span class="info-value mono" :title="provider.baseUrl || ''">
          {{ provider.baseUrl || t('settings.model.notSet') }}
        </span>
      </div>
      <div class="info-row">
        <span class="info-label">{{ t('settings.model.apiKey') }}</span>
        <span class="info-value mono">{{ provider.apiKey || t('settings.model.notSet') }}</span>
      </div>
      <div class="info-row">
        <span class="info-label">{{ t('settings.fields.modelName') }}</span>
        <span class="info-value">
          {{ t('settings.model.modelCount', { count: (provider.models?.length || 0) + (provider.extraModels?.length || 0) }) }}
        </span>
      </div>
    </div>

    <div class="card-actions">
      <button class="card-btn" @click="$emit('manage-models', provider)">
        {{ t('settings.model.actions.manageModels') }}
      </button>
      <button class="card-btn" @click="$emit('provider-settings', provider)">
        {{ t('settings.model.actions.providerSettings') }}
      </button>
      <button
        v-if="provider.supportConnectionCheck && provider.configured"
        class="card-btn"
        :class="{ testing: connectionTestingId === provider.id }"
        :disabled="connectionTestingId === provider.id"
        @click="$emit('test-connection', provider)"
      >
        {{ connectionTestingId === provider.id ? t('settings.model.discovery.testing') : t('settings.model.discovery.testConnection') }}
      </button>
      <button
        v-if="provider.isCustom"
        class="card-btn danger"
        @click="$emit('delete-provider', provider)"
      >
        {{ t('common.delete') }}
      </button>
    </div>

    <div v-if="connectionResults[provider.id]" class="connection-result" :class="connectionResults[provider.id].success ? 'success' : 'error'">
      <span v-if="connectionResults[provider.id].success">
        {{ t('settings.model.discovery.connectionOk') }} · {{ t('settings.model.discovery.latency', { ms: connectionResults[provider.id].latencyMs }) }}
      </span>
      <span v-else>
        {{ t('settings.model.discovery.connectionFail') }}: {{ connectionResults[provider.id].errorMessage }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ProviderInfo } from '@/types'

defineProps<{
  provider: ProviderInfo
  connectionTestingId: string | null
  connectionResults: Record<string, any>
  isProviderActive: (provider: ProviderInfo) => boolean
  providerStatus: (provider: ProviderInfo) => { type: string; label: string }
  getProviderIcon: (id: string) => string
  onIconError: (e: Event) => void
}>()

defineEmits<{
  'manage-models': [provider: ProviderInfo]
  'provider-settings': [provider: ProviderInfo]
  'test-connection': [provider: ProviderInfo]
  'delete-provider': [provider: ProviderInfo]
}>()

const { t } = useI18n()
</script>

<style scoped>
.provider-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.provider-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.provider-icon { width: 28px; height: 28px; border-radius: 6px; object-fit: contain; flex-shrink: 0; }
.provider-name { margin: 0; font-size: 18px; color: var(--mc-text-primary); }
.provider-id { margin: 6px 0 0; font-size: 13px; color: var(--mc-primary); }
.provider-badge { display: inline-flex; align-items: center; border-radius: 999px; padding: 3px 9px; font-size: 12px; font-weight: 600; }
.provider-badge.builtin { background: var(--mc-primary-bg); color: var(--mc-primary); }
.provider-badge.custom { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.provider-badge.active { background: rgba(217, 119, 87, 0.12); color: var(--mc-primary-light); }
.provider-status { flex-shrink: 0; padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; }
.provider-status.configured { background: var(--mc-primary-bg); color: var(--mc-primary); }
.provider-status.partial { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.provider-status.unavailable { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.provider-info { display: grid; gap: 10px; }
.info-row { display: flex; justify-content: space-between; gap: 12px; }
.info-label { color: var(--mc-text-secondary); font-size: 13px; }
.info-value { color: var(--mc-text-primary); font-size: 13px; text-align: right; word-break: break-all; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.card-actions { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
.card-btn { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; background: var(--mc-primary-bg); color: var(--mc-primary); }
.card-btn:hover { background: rgba(217, 119, 87, 0.18); }
.card-btn.danger { background: var(--mc-danger-bg); color: var(--mc-danger); }
.card-btn.testing { opacity: 0.6; cursor: wait; }
.connection-result { margin-top: 10px; padding: 8px 12px; border-radius: 8px; font-size: 12px; }
.connection-result.success { background: var(--mc-primary-bg); color: var(--mc-primary); }
.connection-result.error { background: var(--mc-danger-bg); color: var(--mc-danger); }
</style>
