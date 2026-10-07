import { createRouter, createWebHistory } from "vue-router";
import { readSessionToken } from "../api/client";
import RepositoryView from "../views/RepositoryView.vue";
import PullRequestsView from "../views/PullRequestsView.vue";
import PullRequestDetailView from "../views/PullRequestDetailView.vue";
import ActionsView from "../views/ActionsView.vue";
import SettingsView from "../views/SettingsView.vue";
import AuthView from "../views/AuthView.vue";

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/login",
      name: "login",
      component: AuthView,
      meta: { public: true },
    },
    { path: "/", redirect: "/code" },
    { path: "/code", name: "code", component: RepositoryView },
    {
      path: "/pull-requests",
      name: "pull-requests",
      component: PullRequestsView,
    },
    {
      path: "/pull-requests/:id",
      name: "pull-request-detail",
      component: PullRequestDetailView,
    },
    { path: "/actions", name: "actions", component: ActionsView },
    { path: "/settings", name: "settings", component: SettingsView },
    { path: "/:pathMatch(.*)*", redirect: "/code" },
  ],
});

router.beforeEach((to) => {
  const token = readSessionToken();
  if (!to.meta.public && !token) {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
  if (to.path === "/login" && token) return "/code";
});

window.addEventListener("codetrove:unauthorized", () => {
  if (router.currentRoute.value.path !== "/login") {
    void router.replace({ path: "/login", query: { expired: "1" } });
  }
});

export default router;
