<script setup lang="ts">
defineProps<{ size?: number }>();
</script>

<template>
  <svg
    class="cat-trove-mark"
    :width="size ?? 24"
    :height="size ?? 24"
    viewBox="0 0 24 24"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
  >
    <path
      d="M5.2 8.4 6.1 3.8l3.1 2.3c.9-.3 1.8-.4 2.8-.4s1.9.1 2.8.4l3.1-2.3.9 4.6c1.2 1.2 1.9 2.8 1.9 4.7 0 4-3.8 7.1-8.7 7.1s-8.7-3.1-8.7-7.1c0-1.9.7-3.5 1.9-4.7Z"
      stroke="currentColor"
      stroke-width="1.7"
      stroke-linejoin="round"
    />
    <path
      d="M7.7 12.4h2.1M14.2 12.4h2.1M10.1 16.1c.6.5 1.2.7 1.9.7s1.3-.2 1.9-.7"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
    />
    <path
      d="m11.2 14.2.8.7.8-.7"
      stroke="currentColor"
      stroke-width="1.35"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
  </svg>
</template>
