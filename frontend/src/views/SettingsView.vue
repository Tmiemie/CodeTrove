<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  AlertTriangle,
  Check,
  ChevronDown,
  Eye,
  GitBranch,
  LockKeyhole,
  Save,
  ShieldCheck,
  Trash2,
  Users,
} from "lucide-vue-next";
import { useWorkspaceStore } from "../stores/workspace";

const store = useWorkspaceStore();
const { t } = useI18n();
const repoName = ref("CodeTrove");
const repositoryDescription = ref(t("settings.descriptionValue"));
const defaultBranch = ref("main");
const visibility = ref("private");
const requireChecks = ref(true);
const dismissReviews = ref(true);
const requireConversationResolution = ref(true);
const allowForcePush = ref(false);
const showDanger = ref(false);
</script>

<template>
  <section class="content-page settings-page">
    <header class="page-heading">
      <h1>{{ t("settings.title") }}</h1>
      <p>{{ t("settings.description") }}</p>
    </header>

    <div class="settings-layout">
      <nav class="settings-nav" :aria-label="t('settings.sections')">
        <a href="#general" class="active">{{ t("settings.general") }}</a
        ><a href="#access">{{ t("settings.collaborators") }}</a
        ><a href="#branches">{{ t("settings.branches") }}</a
        ><a href="#security">{{ t("settings.security") }}</a>
      </nav>

      <div class="settings-content">
        <section id="general" class="settings-section">
          <h2>{{ t("settings.general") }}</h2>
          <div class="settings-card form-card">
            <label
              >{{ t("settings.repositoryName") }}<input v-model="repoName"
            /></label>
            <label
              >{{ t("settings.repositoryDescription")
              }}<textarea v-model="repositoryDescription"></textarea>
            </label>
            <div class="form-row">
              <label
                >{{ t("settings.defaultBranch")
                }}<span class="select-field"
                  ><GitBranch :size="15" /><select v-model="defaultBranch">
                    <option>main</option>
                    <option>develop</option></select
                  ><ChevronDown :size="13" /></span
              ></label>
              <label
                >{{ t("settings.visibility")
                }}<span class="select-field"
                  ><component
                    :is="visibility === 'private' ? LockKeyhole : Eye"
                    :size="15" /><select v-model="visibility">
                    <option value="private">{{ t("common.private") }}</option>
                    <option value="public">
                      {{ t("common.public") }}
                    </option></select
                  ><ChevronDown :size="13" /></span
              ></label>
            </div>
            <div class="form-actions">
              <span v-if="store.settingsSavedAt"
                ><Check :size="15" />
                {{
                  t("settings.savedAt", { time: store.settingsSavedAt })
                }}</span
              ><button
                class="button button-primary"
                type="button"
                @click="store.saveSettings"
              >
                <Save :size="15" /> {{ t("common.save") }}
              </button>
            </div>
          </div>
        </section>

        <section id="access" class="settings-section">
          <h2>{{ t("settings.access") }}</h2>
          <div class="settings-card access-card">
            <div>
              <Users :size="20" />
              <div>
                <strong>{{ t("settings.repositoryCollaborators") }}</strong>
                <p>{{ t("settings.peopleAccess", { count: 2 }) }}</p>
              </div>
            </div>
            <button
              class="button button-muted"
              type="button"
              @click="store.notifyKey('settings.collaboratorOpened')"
            >
              {{ t("settings.manageAccess") }}
            </button>
          </div>
        </section>

        <section id="branches" class="settings-section">
          <h2>{{ t("settings.branchProtection") }}</h2>
          <div class="settings-card protection-card">
            <header>
              <ShieldCheck :size="20" />
              <div>
                <strong>{{ t("settings.protectBranches") }}</strong>
                <p>{{ t("settings.rulesApply") }}</p>
              </div>
            </header>
            <label class="check-setting"
              ><input v-model="requireChecks" type="checkbox" /><span
                ><strong>{{ t("settings.requireChecks") }}</strong
                ><small>{{ t("settings.requireChecksHelp") }}</small></span
              ></label
            >
            <label class="check-setting"
              ><input v-model="dismissReviews" type="checkbox" /><span
                ><strong>{{ t("settings.dismissApprovals") }}</strong
                ><small>{{ t("settings.dismissApprovalsHelp") }}</small></span
              ></label
            >
            <label class="check-setting"
              ><input
                v-model="requireConversationResolution"
                type="checkbox"
              /><span
                ><strong>{{ t("settings.resolveConversations") }}</strong
                ><small>{{
                  t("settings.resolveConversationsHelp")
                }}</small></span
              ></label
            >
            <label class="check-setting"
              ><input v-model="allowForcePush" type="checkbox" /><span
                ><strong>{{ t("settings.allowForcePush") }}</strong
                ><small>{{ t("settings.allowForcePushHelp") }}</small></span
              ></label
            >
            <footer>
              <button
                class="button button-primary"
                type="button"
                @click="store.saveSettings"
              >
                {{ t("settings.saveProtection") }}
              </button>
            </footer>
          </div>
        </section>

        <section id="security" class="settings-section danger-zone">
          <h2>{{ t("settings.dangerZone") }}</h2>
          <div class="settings-card danger-card">
            <div>
              <AlertTriangle :size="20" />
              <div>
                <strong>{{ t("settings.archiveRepository") }}</strong>
                <p>{{ t("settings.archiveHelp") }}</p>
              </div>
            </div>
            <button
              class="button button-danger-outline"
              type="button"
              @click="showDanger = !showDanger"
            >
              {{ t("settings.archiveAction") }}
            </button>
          </div>
          <div v-if="showDanger" class="danger-confirm">
            <p>
              <strong>{{ t("settings.archiveWarning") }}</strong>
              {{ t("settings.archiveInstruction", { name: repoName }) }}
            </p>
            <button
              class="button button-danger"
              type="button"
              @click="
                showDanger = false;
                store.notifyKey('settings.archiveCancelled');
              "
            >
              <Trash2 :size="15" /> {{ t("settings.cancelArchive") }}
            </button>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>
