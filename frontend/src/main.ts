import { createApp } from "vue";
import { createPinia } from "pinia";
import "./style.css";
import "./dashboard-theme.css";
import App from "./App.vue";
import router from "./router";
import { i18n, initializeDocumentLocale } from "./i18n";

initializeDocumentLocale();
createApp(App).use(createPinia()).use(router).use(i18n).mount("#app");
