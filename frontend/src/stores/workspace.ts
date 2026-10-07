import { defineStore } from "pinia";
import { ref } from "vue";
import { i18n } from "../i18n";

export const useWorkspaceStore = defineStore("workspace", () => {
  const searchQuery = ref("");
  const toast = ref("");
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

  return { searchQuery, toast, notify, notifyKey };
});
