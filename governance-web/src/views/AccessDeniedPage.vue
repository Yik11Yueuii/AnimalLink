<template>
  <main class="access-page"><el-result icon="error" title="无权限访问" sub-title="前端提示不替代服务端权限校验。请使用具备治理权限的正式会话。"><template #extra><el-button type="primary" @click="$router.push('/')">返回概览</el-button></template></el-result></main>
</template>
