<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import {
  AlertTriangle,
  Box,
  GitBranch,
  LockKeyhole,
  ShieldCheck,
  Users,
} from "lucide-vue-next";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();
const { t, tm } = useI18n();
const repository = computed(() => session.currentRepository);
const unsupported = computed(
  () => tm("m45.settings.unsupported") as readonly string[],
);
</script>

<template>
  <section class="content-page settings-page">
    <header class="page-heading">
      <h1>{{ t("m45.settings.title") }}</h1>
      <p>{{ t("m45.settings.description") }}</p>
    </header>
    <div v-if="!repository" class="empty-state">
      {{ t("m45.shared.selectRepository") }}
    </div>
    <div v-else class="settings-content boundary-settings">
      <section class="settings-section">
        <h2>{{ t("m45.settings.metadata") }}</h2>
        <div class="settings-card readonly-grid">
          <div>
            <Box :size="19" /><span
              ><small>{{ t("m45.settings.name") }}</small
              ><strong
                >{{ repository.owner }} / {{ repository.name }}</strong
              ></span
            >
          </div>
          <div>
            <LockKeyhole :size="19" /><span
              ><small>{{ t("m45.settings.visibility") }}</small
              ><strong>{{ repository.visibility }}</strong></span
            >
          </div>
          <div>
            <GitBranch :size="19" /><span
              ><small>{{ t("m45.settings.defaultBranch") }}</small
              ><strong>{{ repository.defaultBranch }}</strong></span
            >
          </div>
          <div>
            <ShieldCheck :size="19" /><span
              ><small>{{ t("m45.settings.yourRole") }}</small
              ><strong>{{ repository.currentUserRole || "none" }}</strong></span
            >
          </div>
        </div>
      </section>
      <section class="settings-section">
        <h2>{{ t("m45.settings.gitEndpoint") }}</h2>
        <div class="settings-card readonly-block">
          <code>{{ repository.gitHttpUrl }}</code>
          <p>{{ t("m45.settings.gitHelp") }}</p>
        </div>
      </section>
      <section class="settings-section">
        <h2>{{ t("m45.settings.unavailable") }}</h2>
        <div class="settings-card unsupported-card">
          <header>
            <AlertTriangle :size="20" />
            <div>
              <strong>{{ t("m45.settings.noFalseSuccess") }}</strong>
              <p>{{ t("m45.settings.boundaryHelp") }}</p>
            </div>
          </header>
          <ul>
            <li v-for="item in unsupported" :key="item">
              <Users :size="15" />{{ item }}
            </li>
          </ul>
        </div>
      </section>
    </div>
  </section>
</template>
