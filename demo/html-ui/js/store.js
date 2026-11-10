/* ============================================================
   Shared state + copy. No build step: everything is a classic script.
   ============================================================ */

(function () {
  'use strict';

  const COPY = {
    en: {
      brand: 'DiPlay',
      carHome: 'Car home',
      kicker: 'YOUR PHONE. YOUR DRIVE.',
      title: 'A familiar drive.',
      body1: 'Your maps, music and conversations.',
      body2: 'CarPlay, right here on your car display.',
      eyebrow: 'WIRELESS CARPLAY',
      ready: 'Ready when you are',
      connecting: 'Connecting\u2026',
      connected: 'CarPlay is running',
      failed: 'Connection failed',
      connect: 'Connect phone',
      open: 'Open CarPlay',
      disconnect: 'Disconnect',
      retry: 'Try again',
      choose: 'Choose iPhone',
      usb: 'Connect with USB',
      settings: 'Settings',
      back: 'Back',
      search: 'Search',
      searchPlaceholder: 'Search settings',
      searchJump: 'Jump to',
      searchEmpty: 'No settings match \u201c{q}\u201d',
      quickSettings: 'Quick settings',
      yourSetup: 'Your setup',
      tools: 'Tools',
      readyDetail: 'Your iPhone is paired and ready.',
      notWired: 'Prototype only \u2014 this action isn\u2019t wired yet.',
      toastUpdates: 'DiPlay 0.1.0 is the newest build.',
      toastRepo: 'github.com/serein-morii/DiPlay-CN',
      toastSetup: 'The connection setup flow is the next screen.',
      toastIccoaSoon: 'ICCOA pairing comes next.',
      pairEnterCode: 'Enter this code on your phone',
      pairValid: 'Valid for 120 seconds',
      pairWaiting: 'Waiting for your phone\u2026',
      pairConnected: 'Connected',
      pairCarplayPrompt: 'Complete CarPlay on your iPhone',
      pairCarplayHint: 'Keep your iPhone unlocked and allow CarPlay when asked.',
      pairCarplayStepFind: 'Find iPhone over USB',
      pairCarplayStepUsb: 'Allow USB access',
      pairCarplayStepPair: 'Pair with iPhone',
      pairCarplayStepOpen: 'Open CarPlay',
      pairCarplayStepHotspot: 'Start CarPlay Wi-Fi',
      pairCarplayStepPaired: 'Wait for the paired iPhone',
      pairCarplayStepBluetooth: 'Connect Bluetooth',
      projectionHint: 'Your phone screen will appear here.',
      projectionCarplayHint: 'Your iPhone screen will appear here.',
      make: 'Make DiPlay feel right for your car.',
      preview: 'PUBLIC PREVIEW',
      publicPreview: 'Public preview',
      hint1: 'Pair your iPhone with the car\u2019s Bluetooth, then connect.',
      hint2: 'Keep Bluetooth and Wi-Fi on.',
      hintConnecting: 'Keep Bluetooth and Wi-Fi on.',
      hintConnected: 'Connected to {phone} \u00b7 {mode}.',
      hintUsb: 'Connected over USB. You can unplug once CarPlay is on.',
      hotspotOff: 'Car Wi-Fi is off. Turn it on to connect wirelessly.',
      failedHint: 'iPhone didn\u2019t respond. Check Bluetooth and try again.',
      usbHint1: 'Plug your iPhone into a USB data port.',
      usbHint2: 'Allow CarPlay when your iPhone asks.',
      phonesTitle: 'Choose iPhone',
      phonesHint: 'Nearby iPhones \u00b7 demo data',
      scanning: 'Scanning nearby iPhones\u2026',
      rescan: 'Scan again',
      cancel: 'Cancel',
      paired: 'Paired',
      nearby: 'Nearby',
      modeWireless: 'wireless',
      modeUsb: 'USB',
      wirelessTitle: 'Wireless',
      toastSelected: '{name} selected',
      toastAlready: 'CarPlay is already running',
      toastWireless: 'CarPlay connected over Wi-Fi',
      toastUsb: 'CarPlay connected over USB',
      toastDisconnected: 'CarPlay disconnected',
      toastCarHome: 'Returning to the car home screen',
      toastSettings: 'Settings is the next surface \u2014 this pass covers the home screen',
      compareRef: 'Reference',
      compareLive: 'Live',
    },
    zh: {
      brand: 'DiPlay',
      carHome: '\u8f66\u673a\u4e3b\u9875',
      kicker: '\u4f60\u7684\u624b\u673a\uff0c\u7545\u4eab\u65c5\u7a0b\u3002',
      title: '\u719f\u6089\u7684\u9a7e\u4e58\u4f53\u9a8c\u3002',
      body1: '\u5730\u56fe\u3001\u97f3\u4e50\u4e0e\u901a\u8bdd\uff0c',
      body2: '\u5728\u8f66\u673a\u5c4f\u5e55\u4e0a\u7545\u4eab CarPlay\u3002',
      eyebrow: '\u65e0\u7ebf CarPlay',
      ready: '\u4e00\u5207\u5c31\u7eea',
      connecting: '\u6b63\u5728\u8fde\u63a5\u2026',
      connected: 'CarPlay \u8fd0\u884c\u4e2d',
      failed: '\u8fde\u63a5\u5931\u8d25',
      connect: '\u8fde\u63a5\u624b\u673a',
      open: '\u6253\u5f00 CarPlay',
      disconnect: '\u65ad\u5f00\u8fde\u63a5',
      retry: '\u91cd\u8bd5',
      choose: '\u9009\u62e9 iPhone',
      usb: '\u901a\u8fc7 USB \u8fde\u63a5',
      settings: '\u8bbe\u7f6e',
      back: '\u8fd4\u56de',
      search: '\u641c\u7d22',
      searchPlaceholder: '\u641c\u7d22\u8bbe\u7f6e',
      searchJump: '\u5feb\u901f\u8df3\u8f6c',
      searchEmpty: '\u6ca1\u6709\u5339\u914d\u201c{q}\u201d\u7684\u8bbe\u7f6e',
      quickSettings: '\u5feb\u6377\u8bbe\u7f6e',
      yourSetup: '\u4f60\u7684\u8bbe\u7f6e',
      tools: '\u5de5\u5177',
      readyDetail: 'iPhone \u5df2\u914d\u5bf9\uff0c\u968f\u65f6\u53ef\u4ee5\u8fde\u63a5\u3002',
      notWired: '\u539f\u578b\u9636\u6bb5\uff1a\u8be5\u64cd\u4f5c\u5c1a\u672a\u63a5\u5165\u3002',
      toastUpdates: 'DiPlay 0.1.0 \u5df2\u662f\u6700\u65b0\u7248\u672c\u3002',
      toastRepo: 'github.com/serein-morii/DiPlay-CN',
      toastSetup: '\u8fde\u63a5\u8bbe\u7f6e\u6d41\u7a0b\u7559\u5230\u4e0b\u4e00\u4e2a\u754c\u9762\u3002',
      toastIccoaSoon: 'ICCOA \u914d\u5bf9\u5c06\u5728\u4e0b\u4e00\u8f6e\u63a5\u5165\u3002',
      pairEnterCode: '\u5728\u624b\u673a\u4e0a\u8f93\u5165\u914d\u5bf9\u7801',
      pairValid: '\u6709\u6548\u671f 120 \u79d2',
      pairWaiting: '\u7b49\u5f85\u624b\u673a\u8fde\u63a5\u2026',
      pairConnected: '\u5df2\u8fde\u63a5',
      pairCarplayPrompt: '\u5728 iPhone \u4e0a\u5b8c\u6210 CarPlay \u8fde\u63a5',
      pairCarplayHint: '\u4fdd\u6301 iPhone \u89e3\u9501\uff0c\u5e76\u5728\u63d0\u793a\u65f6\u5141\u8bb8 CarPlay\u3002',
      pairCarplayStepFind: '\u901a\u8fc7 USB \u53d1\u73b0 iPhone',
      pairCarplayStepUsb: '\u5141\u8bb8 USB \u8bbf\u95ee',
      pairCarplayStepPair: '\u4e0e iPhone \u914d\u5bf9',
      pairCarplayStepOpen: '\u6253\u5f00 CarPlay',
      pairCarplayStepHotspot: '\u542f\u52a8 CarPlay Wi-Fi',
      pairCarplayStepPaired: '\u7b49\u5f85\u5df2\u914d\u5bf9\u7684 iPhone',
      pairCarplayStepBluetooth: '\u8fde\u63a5\u84dd\u7259',
      projectionHint: '\u624b\u673a\u753b\u9762\u5c06\u663e\u793a\u5728\u8fd9\u91cc\u3002',
      projectionCarplayHint: 'iPhone \u753b\u9762\u5c06\u663e\u793a\u5728\u8fd9\u91cc\u3002',
      make: '\u6309\u9700\u8c03\u6574 DiPlay \u8bbe\u7f6e\u3002',
      preview: '\u516c\u5f00\u9884\u89c8',
      publicPreview: '\u516c\u5f00\u9884\u89c8',
      hint1: '\u5c06 iPhone \u4e0e\u8f66\u673a\u84dd\u7259\u914d\u5bf9\u540e\u8fde\u63a5\u3002',
      hint2: '\u4fdd\u6301\u84dd\u7259\u548c Wi-Fi \u5f00\u542f\u3002',
      hintConnecting: '\u4fdd\u6301\u84dd\u7259\u548c Wi-Fi \u5f00\u542f\u3002',
      hintConnected: '\u5df2\u8fde\u63a5 {phone} \u00b7 {mode}\u3002',
      hintUsb: '\u5df2\u901a\u8fc7 USB \u8fde\u63a5\uff0cCarPlay \u542f\u52a8\u540e\u53ef\u62d4\u6389\u6570\u636e\u7ebf\u3002',
      hotspotOff: '\u8f66\u673a Wi-Fi \u672a\u5f00\u542f\uff0c\u8bf7\u5f00\u542f\u540e\u4f7f\u7528\u65e0\u7ebf\u8fde\u63a5\u3002',
      failedHint: 'iPhone \u65e0\u54cd\u5e94\uff0c\u8bf7\u68c0\u67e5\u84dd\u7259\u540e\u91cd\u8bd5\u3002',
      usbHint1: '\u5c06 iPhone \u8fde\u63a5\u5230\u652f\u6301\u6570\u636e\u4f20\u8f93\u7684 USB \u63a5\u53e3\u3002',
      usbHint2: '\u624b\u673a\u51fa\u73b0\u63d0\u793a\u65f6\u5141\u8bb8\u4f7f\u7528 CarPlay\u3002',
      phonesTitle: '\u9009\u62e9 iPhone',
      phonesHint: '\u9644\u8fd1\u7684 iPhone \u00b7 \u6f14\u793a\u6570\u636e',
      scanning: '\u6b63\u5728\u641c\u7d22\u9644\u8fd1\u7684 iPhone\u2026',
      rescan: '\u91cd\u65b0\u641c\u7d22',
      cancel: '\u53d6\u6d88',
      paired: '\u5df2\u914d\u5bf9',
      nearby: '\u9644\u8fd1',
      modeWireless: '\u65e0\u7ebf',
      modeUsb: 'USB',
      wirelessTitle: '\u65e0\u7ebf',
      toastSelected: '\u5df2\u9009\u62e9 {name}',
      toastAlready: 'CarPlay \u5df2\u5728\u8fd0\u884c',
      toastWireless: 'CarPlay \u5df2\u901a\u8fc7 Wi-Fi \u8fde\u63a5',
      toastUsb: 'CarPlay \u5df2\u901a\u8fc7 USB \u8fde\u63a5',
      toastDisconnected: 'CarPlay \u5df2\u65ad\u5f00',
      toastCarHome: '\u6b63\u5728\u8fd4\u56de\u8f66\u673a\u4e3b\u9875',
      toastSettings: '\u8bbe\u7f6e\u754c\u9762\u7559\u7ed9\u4e0b\u4e00\u8f6e\uff0c\u672c\u6b21\u5148\u770b\u4e3b\u9875',
      compareRef: '\u53c2\u8003\u56fe',
      compareLive: '\u5f53\u524d\u6548\u679c',
    },
  };

  // Accent presets keep the same "soft on dark, deep on light" contract as the palette.
  const ACCENTS = {
    lumos: { name: 'Lumos', dark: '#a6c8ff', light: '#245a9c', onDark: '#0c111b', onLight: '#ffffff' },
    frost: { name: 'Frost', dark: '#9fdec8', light: '#1d6453', onDark: '#08150f', onLight: '#ffffff' },
    amber: { name: 'Amber', dark: '#f2c98e', light: '#7a4500', onDark: '#171006', onLight: '#ffffff' },
    lilac: { name: 'Lilac', dark: '#cdbcff', light: '#563a9e', onDark: '#0d0a18', onLight: '#ffffff' },
  };

  const PHONES = [
    { name: 'Colin\u2019s iPhone', paired: true, signal: 3 },
    { name: 'iPhone 15 Pro', paired: false, signal: 2 },
    { name: 'iPhone 13', paired: false, signal: 1 },
  ];

  const STORAGE_KEY = 'diplay.lumos.lab.v1';

  const DESIGN_DEFAULTS = {
    lang: 'en',
    appearance: 'light',
    accent: 'lumos',
    radius: 20,
    motion: true,
    chrome: true,
    grid: false,
    compare: false,
    compareX: 50,
    clean: false,
    tweaksOpen: false,
    homeStyle: 'glass',
    transport: 'usb',
  };

  function readSaved() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return {};
      const value = JSON.parse(raw);
      return value && typeof value === 'object' ? value : {};
    } catch (error) {
      return {};
    }
  }

  function readParams() {
    const params = new URLSearchParams(window.location.search);
    const out = {};
    const lang = params.get('lang');
    if (lang === 'en' || lang === 'zh') out.lang = lang;
    const theme = params.get('theme');
    if (theme === 'dark' || theme === 'light') out.appearance = theme;
    const accent = params.get('accent');
    if (accent && ACCENTS[accent]) out.accent = accent;
    const home = params.get('home');
    if (home === 'glass' || home === 'replica') out.homeStyle = home;
    if (params.get('clean') === '1') out.clean = true;
    if (params.get('compare') === '1') out.compare = true;
    return out;
  }

  const saved = readSaved();
  const params = readParams();

  // Settings are stored per scope: shared, carplay, iccoa. Flat profiles from
  // the previous build are migrated by looking each key up in the settings tree.
  function readPrefs(raw) {
    const defaults = DiPlaySettingsData.defaults;
    const out = {
      shared: { ...defaults.shared, ...(raw.shared || {}) },
      carplay: { ...defaults.carplay, ...(raw.carplay || {}) },
      iccoa: { ...defaults.iccoa, ...(raw.iccoa || {}) },
    };
    for (const [key, value] of Object.entries(raw)) {
      if (key === 'shared' || key === 'carplay' || key === 'iccoa') continue;
      const scope = DiPlaySettingsData.scopeOfKey[key] || 'shared';
      out[scope][key] = value;
    }
    return out;
  }

  const prefs = readPrefs(saved.prefs || {});

  function systemDark() {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches
      ? 'light'
      : 'dark';
  }

  function systemLang() {
    return String(navigator.language || 'en').toLowerCase().startsWith('zh') ? 'zh' : 'en';
  }

  function resolveAppearance(mode) {
    return mode === 'auto' ? systemDark() : mode === 'light' ? 'light' : 'dark';
  }

  function resolveLanguage(mode) {
    return mode === 'system' ? systemLang() : mode === 'zh' ? 'zh' : 'en';
  }

  const store = Vue.reactive({
    ...DESIGN_DEFAULTS,
    version: '0.1.0',
    view: 'home',
    settingsCategory: 'general',
    settingsSearch: false,
    settingsQuery: '',
    settingsHighlight: '',
    session: 'idle',
    method: 'wireless',
    phoneName: PHONES[0].name,
    chosen: true,
    sheet: null,
    scanning: false,
    toast: null,
    scenario: 'idle',
    failNext: false,
    phones: PHONES.map((phone) => ({ ...phone })),
    ...saved,
    ...params,
    prefs,
  });

  // URL params win, but they should not leak into the saved profile.
  store.tweaksOpen = false;

  // Keep the settings UI truthful when a demo URL overrides the theme or language.
  if (params.lang) store.prefs.shared.language = params.lang;
  if (params.appearance) store.prefs.shared.appearance = params.appearance;

  // A stale profile must never break a render.
  if (!COPY[store.lang]) store.lang = 'en';
  if (store.appearance !== 'light') store.appearance = 'dark';
  if (!ACCENTS[store.accent]) store.accent = 'lumos';
  store.radius = Math.min(28, Math.max(8, Number(store.radius) || 20));

  // Settings preferences are the source of truth for appearance, language and system bars.
  if (!params.lang) store.lang = resolveLanguage(store.prefs.shared.language);
  if (!params.appearance) store.appearance = resolveAppearance(store.prefs.shared.appearance);
  store.chrome = !store.prefs.shared.fullScreen;

  // Profiles from before the glass home move to the light sky once; language and
  // accent survive, and an explicit ?theme= still wins.
  if (saved.homeStyle === undefined && !params.appearance) {
    store.appearance = 'light';
    store.prefs.shared.appearance = 'light';
  }

  // Demo deep links: ?state=connected|error|hotspot&sheet=phones&tweaks=1
  (function applyDemoParams() {
    const params = new URLSearchParams(window.location.search);
    const state = params.get('state');
    if (state === 'connected') {
      store.session = 'connected';
      store.method = params.get('usb') === '1' ? 'usb' : 'wireless';
    } else if (state === 'error') {
      store.session = 'error';
      store.failNext = true;
    } else if (state === 'hotspot') {
      store.scenario = 'hotspot-off';
    }
    if (params.get('sheet') === 'phones') {
      store.sheet = 'phones';
      store.scanning = true;
      window.setTimeout(() => {
        store.scanning = false;
      }, 500);
    }
    if (params.get('tweaks') === '1') store.tweaksOpen = true;
    if (params.get('view') === 'settings') {
      store.view = 'settings';
      const category = params.get('category');
      if (category && DiPlaySettingsData.getCategory(category).id === category) {
        store.settingsCategory = category;
      }
    }
  })();

  function copy() {
    return COPY[store.lang] || COPY.en;
  }

  function format(template, values) {
    return String(template).replace(/\{(\w+)\}/g, (match, key) =>
      Object.prototype.hasOwnProperty.call(values, key) ? values[key] : match,
    );
  }

  let toastTimer = 0;
  let connectTimer = 0;
  let scanTimer = 0;
  let highlightTimer = 0;

  const actions = {
    toast(message) {
      window.clearTimeout(toastTimer);
      store.toast = message;
      toastTimer = window.setTimeout(() => {
        store.toast = null;
      }, 2600);
    },

    connect(method) {
      const t = copy();
      if (store.session === 'connecting') return;
      if (store.session === 'connected') {
        actions.toast(t.toastAlready);
        return;
      }
      if (!store.chosen) {
        actions.openPhones();
        return;
      }
      window.clearTimeout(connectTimer);
      store.method = method === 'usb' ? 'usb' : 'wireless';
      store.scenario = store.scenario === 'hotspot-off' ? 'hotspot-off' : 'idle';
      store.session = 'connecting';
      // The error scenario fails once; "Try again" then succeeds, like a real retry.
      const fails = store.failNext;
      store.failNext = false;
      connectTimer = window.setTimeout(() => {
        if (fails) {
          store.session = 'error';
          return;
        }
        store.session = 'connected';
        actions.toast(store.method === 'usb' ? t.toastUsb : t.toastWireless);
      }, 1900);
    },

    disconnect() {
      window.clearTimeout(connectTimer);
      store.session = 'idle';
      actions.toast(copy().toastDisconnected);
    },

    openPhones() {
      store.sheet = 'phones';
      store.scanning = true;
      window.clearTimeout(scanTimer);
      scanTimer = window.setTimeout(() => {
        store.scanning = false;
      }, 1250);
    },

    rescan() {
      actions.openPhones();
    },

    closeSheet() {
      window.clearTimeout(scanTimer);
      store.sheet = null;
      store.scanning = false;
    },

    choosePhone(phone) {
      store.phoneName = phone.name;
      store.chosen = true;
      actions.closeSheet();
      actions.toast(format(copy().toastSelected, { name: phone.name }));
    },

    goCarHome() {
      actions.toast(copy().toastCarHome);
    },

    openSettings(category) {
      store.view = 'settings';
      if (category) store.settingsCategory = category;
      store.settingsSearch = false;
      store.settingsQuery = '';
    },

    closeSettings() {
      store.view = 'home';
      store.settingsSearch = false;
      store.settingsQuery = '';
    },

    setCategory(id) {
      store.settingsCategory = id;
      store.settingsSearch = false;
    },

    openSearch() {
      store.settingsSearch = true;
    },

    closeSearch() {
      store.settingsSearch = false;
      store.settingsQuery = '';
    },

    setAppearance(mode) {
      store.prefs.shared.appearance = mode === 'auto' ? 'auto' : mode === 'light' ? 'light' : 'dark';
      store.appearance = resolveAppearance(store.prefs.shared.appearance);
    },

    setLanguage(mode) {
      store.prefs.shared.language = mode === 'zh' || mode === 'system' ? mode : 'en';
      store.lang = resolveLanguage(store.prefs.shared.language);
    },

    setFullScreen(value) {
      store.prefs.shared.fullScreen = !!value;
      store.chrome = !value;
    },

    setHomeStyle(style) {
      store.homeStyle = style === 'replica' ? 'replica' : 'glass';
      // The replica is measured against the dark reference screenshot.
      if (store.homeStyle === 'replica') actions.setAppearance('dark');
    },

    setTransport(value) {
      store.transport = value === 'wifi' ? 'wifi' : 'usb';
    },

    getPref(key, scope = 'shared') {
      const bucket = store.prefs[scope] || store.prefs.shared;
      return bucket[key];
    },

    setPref(key, value, scope = 'shared') {
      if (scope === 'shared' || !store.prefs[scope]) {
        if (key === 'appearance') {
          actions.setAppearance(value);
          return;
        }
        if (key === 'language') {
          actions.setLanguage(value);
          return;
        }
        if (key === 'fullScreen') {
          actions.setFullScreen(value);
          return;
        }
      }
      const bucket = store.prefs[scope] || store.prefs.shared;
      bucket[key] = value;
    },

    highlightSetting(id) {
      window.clearTimeout(highlightTimer);
      store.settingsHighlight = id;
      highlightTimer = window.setTimeout(() => {
        store.settingsHighlight = '';
      }, 2200);
    },

    runSettingsAction(id) {
      const t = copy();
      if (id === 'choosePhone') {
        actions.openPhones();
        return;
      }
      if (id === 'checkUpdates') {
        actions.toast(t.toastUpdates);
        return;
      }
      if (id === 'repo') {
        actions.toast(t.toastRepo);
        return;
      }
      if (id === 'setup') {
        actions.toast(t.toastSetup);
        return;
      }
      actions.toast(t.notWired);
    },

    setScenario(value) {
      window.clearTimeout(connectTimer);
      if (value === 'connected') {
        store.method = 'wireless';
        store.session = 'connected';
        store.scenario = 'idle';
        store.failNext = false;
      } else if (value === 'error') {
        store.session = 'error';
        store.scenario = 'idle';
        store.failNext = true;
      } else if (value === 'hotspot') {
        store.session = 'idle';
        store.scenario = 'hotspot-off';
        store.failNext = false;
      } else {
        store.session = 'idle';
        store.scenario = 'idle';
        store.failNext = false;
      }
    },

    reset() {
      window.clearTimeout(toastTimer);
      window.clearTimeout(connectTimer);
      window.clearTimeout(scanTimer);
      window.clearTimeout(highlightTimer);
      Object.assign(store, DESIGN_DEFAULTS, {
        version: '0.1.0',
        session: 'idle',
        method: 'wireless',
        phoneName: PHONES[0].name,
        chosen: true,
        sheet: null,
        scanning: false,
        toast: null,
        scenario: 'idle',
        failNext: false,
        view: 'home',
        settingsCategory: 'general',
        settingsSearch: false,
        settingsQuery: '',
        settingsHighlight: '',
        prefs: {
          shared: { ...DiPlaySettingsData.defaults.shared },
          carplay: { ...DiPlaySettingsData.defaults.carplay },
          iccoa: { ...DiPlaySettingsData.defaults.iccoa },
        },
      });
      try {
        localStorage.removeItem(STORAGE_KEY);
      } catch (error) {
        /* private mode */
      }
    },
  };

  let saveTimer = 0;
  function save() {
    window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(() => {
      try {
        localStorage.setItem(
          STORAGE_KEY,
          JSON.stringify({
            lang: store.lang,
            appearance: store.appearance,
            accent: store.accent,
            radius: store.radius,
            motion: store.motion,
            chrome: store.chrome,
            grid: store.grid,
            homeStyle: store.homeStyle,
            transport: store.transport,
            prefs: { ...store.prefs },
          }),
        );
      } catch (error) {
        /* private mode */
      }
    }, 250);
  }

  Vue.watch(
    () => [
      store.lang,
      store.appearance,
      store.accent,
      store.radius,
      store.motion,
      store.chrome,
      store.grid,
    ],
    save,
  );

  Vue.watch(() => store.prefs, save, { deep: true });

  window.DiPlay = { store, actions, copy, accents: ACCENTS, phones: PHONES, format };
})();

