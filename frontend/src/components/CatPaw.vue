<script setup lang="ts">
defineProps<{ size?: number }>();
</script>

<template>
  <svg
    class="cat-paw"
    :width="size ?? 24"
    :height="size ?? 24"
    viewBox="0 0 24 24"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
  >
    <ellipse cx="12" cy="15.7" rx="5" ry="4.2" fill="currentColor" />
    <ellipse
      cx="5.8"
      cy="10.3"
      rx="2.1"
      ry="2.7"
      transform="rotate(-24 5.8 10.3)"
      fill="currentColor"
    />
    <ellipse
      cx="10"
      cy="6.9"
      rx="2.1"
      ry="2.8"
      transform="rotate(-8 10 6.9)"
      fill="currentColor"
    />
    <ellipse
      cx="14.6"
      cy="6.9"
      rx="2.1"
      ry="2.8"
      transform="rotate(8 14.6 6.9)"
      fill="currentColor"
    />
    <ellipse
      cx="18.6"
      cy="10.5"
      rx="2.1"
      ry="2.7"
      transform="rotate(24 18.6 10.5)"
      fill="currentColor"
    />
  </svg>
</template>
