<template>
  <!-- 根组件只放一个路由出口；全局基础样式（盒模型、字体、背景、.page-card/.muted/.price 通用类）写在下方 <style> 中 -->
  <router-view />
</template>

<style>
* { box-sizing: border-box; }
html, body, #app { height: 100%; margin: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  background: #f5f7fa;
  color: #303133;
}
.page-card { border-radius: 10px; }
.muted { color: #909399; font-size: 12px; }
.price { color: #f56c6c; font-weight: 600; }
</style>
