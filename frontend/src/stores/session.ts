import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { api } from "../api";
import {
  errorMessage,
  readSessionToken,
  writeSessionToken,
} from "../api/client";
import type { Repository, User } from "../api/types";

const REPOSITORY_KEY = "codetrove.session.repository";

export const useSessionStore = defineStore("session", () => {
  const token = ref(readSessionToken());
  const user = ref<User | null>(null);
  const repositories = ref<Repository[]>([]);
  const currentRepository = ref<Repository | null>(null);
  const loading = ref(false);
  const initialized = ref(false);
  const error = ref("");

  const authenticated = computed(() => Boolean(token.value && user.value));

  function clear() {
    token.value = null;
    user.value = null;
    repositories.value = [];
    currentRepository.value = null;
    writeSessionToken(null);
    sessionStorage.removeItem(REPOSITORY_KEY);
  }

  async function initialize() {
    if (initialized.value) return;
    initialized.value = true;
    if (!token.value) return;
    try {
      user.value = (await api.me()).data;
      await loadRepositories();
    } catch {
      clear();
    }
  }

  async function login(username: string, password: string) {
    loading.value = true;
    error.value = "";
    try {
      const result = (await api.login(username, password)).data;
      token.value = result.accessToken;
      user.value = result.user;
      writeSessionToken(result.accessToken);
      await loadRepositories();
    } catch (requestError) {
      clear();
      error.value = errorMessage(requestError);
      throw requestError;
    } finally {
      loading.value = false;
    }
  }

  async function register(
    username: string,
    password: string,
    displayName: string,
  ) {
    loading.value = true;
    error.value = "";
    try {
      await api.register(username, password, displayName);
      await login(username, password);
    } catch (requestError) {
      error.value = errorMessage(requestError);
      throw requestError;
    } finally {
      loading.value = false;
    }
  }

  async function loadRepositories() {
    repositories.value = (await api.repositories()).data;
    const saved = sessionStorage.getItem(REPOSITORY_KEY);
    currentRepository.value =
      repositories.value.find((repository) => repository.id === saved) ??
      repositories.value[0] ??
      null;
    if (currentRepository.value) {
      sessionStorage.setItem(REPOSITORY_KEY, currentRepository.value.id);
    }
  }

  function selectRepository(repository: Repository) {
    currentRepository.value = repository;
    sessionStorage.setItem(REPOSITORY_KEY, repository.id);
  }

  async function createRepository(input: {
    name: string;
    slug: string;
    description: string;
    visibility: "PRIVATE" | "PUBLIC";
    initializeWithReadme: boolean;
  }) {
    loading.value = true;
    error.value = "";
    try {
      const repository = (await api.createRepository(input)).data;
      await loadRepositories();
      selectRepository(repository);
      return repository;
    } catch (requestError) {
      error.value = errorMessage(requestError);
      throw requestError;
    } finally {
      loading.value = false;
    }
  }

  function logout() {
    clear();
  }

  window.addEventListener("codetrove:unauthorized", clear);

  return {
    token,
    user,
    repositories,
    currentRepository,
    loading,
    initialized,
    error,
    authenticated,
    initialize,
    login,
    register,
    logout,
    loadRepositories,
    selectRepository,
    createRepository,
  };
});
