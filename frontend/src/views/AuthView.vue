<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Globe2, LockKeyhole, UserRound } from "lucide-vue-next";
import { useI18n } from "vue-i18n";
import CatTroveMark from "../components/CatTroveMark.vue";
import { setAppLocale, type AppLocale } from "../i18n";
import { useSessionStore } from "../stores/session";

const store = useSessionStore();
const router = useRouter();
const route = useRoute();
const { t, locale } = useI18n();
const mode = ref<"login" | "register">("login");
const username = ref("");
const displayName = ref("");
const password = ref("");
const canSubmit = computed(
  () =>
    username.value.trim().length >= 3 &&
    password.value.length >= 12 &&
    (mode.value === "login" || displayName.value.trim().length > 0),
);

function toggleLocale() {
  const nextLocale: AppLocale = locale.value === "en-US" ? "zh-CN" : "en-US";
  setAppLocale(nextLocale);
}

async function submit() {
  if (!canSubmit.value) return;
  try {
    if (mode.value === "login")
      await store.login(username.value, password.value);
    else
      await store.register(username.value, password.value, displayName.value);
    await router.replace(String(route.query.redirect || "/code"));
  } catch {
    // Store exposes the structured backend error.
  }
}
</script>

<template>
  <main class="auth-page">
    <button
      class="language-switch auth-language"
      type="button"
      :aria-label="t('common.switchLanguage')"
      @click="toggleLocale"
    >
      <Globe2 :size="17" />{{ t("common.language") }}
    </button>
    <section class="auth-card">
      <div class="auth-brand">
        <CatTroveMark :size="44" /><strong>CodeTrove</strong>
      </div>
      <p>{{ t("m45.auth.tagline") }}</p>
      <div class="segmented auth-tabs">
        <button
          :class="{ active: mode === 'login' }"
          type="button"
          @click="mode = 'login'"
        >
          {{ t("m45.auth.signIn") }}
        </button>
        <button
          :class="{ active: mode === 'register' }"
          type="button"
          @click="mode = 'register'"
        >
          {{ t("m45.auth.register") }}
        </button>
      </div>
      <form @submit.prevent="submit">
        <label v-if="mode === 'register'"
          >{{ t("m45.auth.displayName")
          }}<span class="auth-input"
            ><UserRound :size="17" /><input
              v-model="displayName"
              autocomplete="name" /></span
        ></label>
        <label
          >{{ t("m45.auth.username")
          }}<span class="auth-input"
            ><UserRound :size="17" /><input
              v-model="username"
              autocomplete="username" /></span
        ></label>
        <label
          >{{ t("m45.auth.password")
          }}<span class="auth-input"
            ><LockKeyhole :size="17" /><input
              v-model="password"
              type="password"
              :autocomplete="
                mode === 'login' ? 'current-password' : 'new-password'
              " /></span
          ><small>{{ t("m45.auth.passwordHelp") }}</small></label
        >
        <div v-if="store.error" class="api-error" role="alert">
          {{ store.error }}
        </div>
        <button
          class="button button-primary auth-submit"
          :disabled="!canSubmit || store.loading"
        >
          {{
            store.loading
              ? t("m45.auth.connecting")
              : mode === "login"
                ? t("m45.auth.signIn")
                : t("m45.auth.createAccount")
          }}
        </button>
      </form>
    </section>
  </main>
</template>
