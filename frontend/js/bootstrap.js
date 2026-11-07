// Keep the existing page options and routes while using Vue 3's application API.
function mountSmartLife(options) {
  const selector = options.el || "#app";
  const initialData = options.data;
  if (typeof initialData !== "function") {
    options.data = () => initialData;
  }
  delete options.el;
  const application = Vue.createApp(options);
  application.use(ElementPlus, { locale: ElementPlusLocaleZhCn });
  if (typeof SmartLifeFooter !== "undefined") {
    application.component("foot-bar", SmartLifeFooter);
  }
  return application.mount(selector);
}
