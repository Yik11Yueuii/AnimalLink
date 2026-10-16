<template>
  <main class="access-page"><el-result icon="warning" title="需要认证" sub-title="正式身份认证尚未接入，受保护治理模块暂不可用。"><template #extra><el-button type="primary" @click="$router.push('/')">返回概览</el-button></template></el-result></main>
</template>
