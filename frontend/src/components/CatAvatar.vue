<script setup lang="ts">
defineProps<{ size?: number }>();
</script>

<template>
  <svg
    class="cat-avatar-mark"
    :width="size ?? 24"
    :height="size ?? 24"
    viewBox="0 0 24 24"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
  >
    <path
      d="m5.3 8.8.8-5 3.2 2.5a9 9 0 0 1 5.4 0l3.2-2.5.8 5a7.6 7.6 0 0 1 1.5 4.5c0 4.2-3.7 7.2-8.2 7.2s-8.2-3-8.2-7.2c0-1.7.5-3.2 1.5-4.5Z"
      fill="currentColor"
    />
    <path
      d="M8 12.2h1.6M14.4 12.2H16M10.3 16c.5.4 1.1.6 1.7.6s1.2-.2 1.7-.6"
      stroke="var(--cat-face-ink, #2b2a2f)"
      stroke-width="1.4"
      stroke-linecap="round"
    />
    <path
      d="m11.3 14.1.7.6.7-.6"
      stroke="var(--cat-face-ink, #2b2a2f)"
      stroke-width="1.25"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
  </svg>
</template>
