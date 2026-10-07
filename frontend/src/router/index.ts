import { createRouter, createWebHistory } from "vue-router";
import RepositoryView from "../views/RepositoryView.vue";
import PullRequestsView from "../views/PullRequestsView.vue";
import PullRequestDetailView from "../views/PullRequestDetailView.vue";
import ActionsView from "../views/ActionsView.vue";
import SettingsView from "../views/SettingsView.vue";

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
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

export default router;
