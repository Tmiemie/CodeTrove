import { defineStore } from "pinia";
import { ref } from "vue";
import { i18n } from "../i18n";

export const useWorkspaceStore = defineStore("workspace", () => {
  const searchQuery = ref("");
  const toast = ref("");
  const pullRequestTab = ref<"conversation" | "commits" | "checks" | "files">(
    "conversation",
  );
  const comments = ref<string[]>([]);
  const mergeState = ref<"ready" | "merged">("ready");
  const settingsSavedAt = ref("");

  let toastTimer: number | undefined;

  function notify(message: string) {
    toast.value = message;
    if (toastTimer) window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => {
      toast.value = "";
    }, 2600);
  }

  function notifyKey(key: string, params?: Record<string, unknown>) {
    notify(i18n.global.t(key, params ?? {}));
  }

  function addComment(message: string) {
    const value = message.trim();
    if (!value) return false;
    comments.value.push(value);
    notifyKey("pullRequestDetail.commentAdded");
    return true;
  }

  function mergePullRequest() {
    mergeState.value = "merged";
    notifyKey("pullRequestDetail.pullRequestMerged");
  }

  function saveSettings() {
    settingsSavedAt.value = new Intl.DateTimeFormat(i18n.global.locale.value, {
      hour: "2-digit",
      minute: "2-digit",
    }).format(new Date());
    notifyKey("settings.settingsSaved");
  }

  return {
    searchQuery,
    toast,
    pullRequestTab,
    comments,
    mergeState,
    settingsSavedAt,
    notify,
    notifyKey,
    addComment,
    mergePullRequest,
    saveSettings,
  };
});
