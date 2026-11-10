/* ============================================================
   Lab shell: fits the fixed 16:9 panel into the viewport, wires shortcuts.
   ============================================================ */

(function () {
  'use strict';

  const { createApp, ref, computed, watch, onMounted, onBeforeUnmount } = Vue;
  const s = DiPlay.store;

  // Material-style ripple, mapped back into the scaled screen's dp space.
  const ripple = {
    mounted(el) {
      el.addEventListener('pointerdown', (event) => {
        if (!DiPlay.store.motion) return;
        const root = el.closest('.car-screen');
        const k = root ? root.getBoundingClientRect().width / 1280 : 1;
        const rect = el.getBoundingClientRect();
        const width = rect.width / k;
        const height = rect.height / k;
        const size = Math.max(width, height) * 1.9;
        const span = document.createElement('span');
        span.className = 'ripple';
        span.style.width = size + 'px';
        span.style.height = size + 'px';
        span.style.left = (event.clientX - rect.left) / k + 'px';
        span.style.top = (event.clientY - rect.top) / k + 'px';
        el.appendChild(span);
        window.setTimeout(() => span.remove(), 620);
      });
    },
  };

  const app = createApp({
    setup() {
      const host = ref(null);
      const hostWidth = ref(0);
      const hostHeight = ref(0);

      let observer = null;

      function measure() {
        if (!host.value) return;
        hostWidth.value = host.value.clientWidth;
        hostHeight.value = host.value.clientHeight;
      }

      const scale = computed(() => {
        const bezel = s.clean ? 0 : 20;
        const safety = s.clean ? 0 : 2;
        const width = hostWidth.value - bezel - safety;
        const height = hostHeight.value - bezel - safety;
        if (width <= 0 || height <= 0) return 0.2;
        const fit = Math.min(width / 1280, height / 720);
        return Math.min(s.clean ? 4 : 1.6, Math.max(0.15, fit));
      });

      const scalePercent = computed(() => Math.round(scale.value * 100));

      const panelStyle = computed(() => {
        const pad = s.clean ? 0 : 20;
        return {
          width: (1280 * scale.value + pad).toFixed(2) + 'px',
          height: (720 * scale.value + pad).toFixed(2) + 'px',
        };
      });

      const screenBox = computed(() => ({
        width: (1280 * scale.value).toFixed(2) + 'px',
        height: (720 * scale.value).toFixed(2) + 'px',
      }));

      const scalerStyle = computed(() => ({
        transform: 'scale(' + scale.value + ')',
      }));

      function onKeydown(event) {
        const tag = (event.target && event.target.tagName) || '';
        if (/^(INPUT|TEXTAREA|SELECT)$/.test(tag)) return;

        if (event.key === 'Escape') {
          if (s.settingsSearch) DiPlay.actions.closeSearch();
          else if (s.sheet) DiPlay.actions.closeSheet();
          else if (s.tweaksOpen) s.tweaksOpen = false;
          else if (s.view === 'settings') DiPlay.actions.closeSettings();
          return;
        }
        if (event.metaKey || event.ctrlKey || event.altKey) return;

        const key = event.key.toLowerCase();
        if (key === 't') s.tweaksOpen = !s.tweaksOpen;
        else if (key === 'c') s.clean = !s.clean;
        else if (key === 'v') s.compare = !s.compare;
        else if (key === '/' && s.view === 'settings') {
          event.preventDefault();
          DiPlay.actions.openSearch();
        }
      }

      onMounted(() => {
        measure();
        if (window.ResizeObserver) {
          observer = new ResizeObserver(measure);
          observer.observe(host.value);
        }
        window.addEventListener('resize', measure);
        window.addEventListener('keydown', onKeydown);
      });

      onBeforeUnmount(() => {
        if (observer) observer.disconnect();
        window.removeEventListener('resize', measure);
        window.removeEventListener('keydown', onKeydown);
      });

      watch(
        () => s.view,
        (view) => {
          if (view === 'settings' && s.compare) s.compare = false;
        },
      );

      return {
        s,
        host,
        scale,
        scalePercent,
        panelStyle,
        screenBox,
        scalerStyle,
        toggleTheme: () => {
          DiPlay.actions.setAppearance(s.appearance === 'dark' ? 'light' : 'dark');
        },
        setLanguage: (mode) => DiPlay.actions.setLanguage(mode),
      };
    },

    template: `
      <div class="lab" :class="{ 'is-clean': s.clean, 'has-tweaks': s.tweaksOpen }">
        <header class="lab-bar">
          <div class="lab-brand">
            <img src="./assets/carplay.png" alt="">
            <span>DiPlay</span>
          </div>
          <span class="lab-divider"></span>
          <span class="lab-kicker">Lumos UI Lab</span>
          <span class="lab-chip">v0 &middot; Replica</span>
          <span class="lab-spacer"></span>

          <button
            class="lab-btn"
            type="button"
            :class="{ 'is-on': s.compare }"
            :aria-pressed="s.compare"
            :disabled="s.view === 'settings' || s.homeStyle === 'glass'"
            @click="s.compare = !s.compare"
          >
            <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M8 1v14M3 4.5h3M3 8h2M3 11.5h3M10 4.5h3M11 8h2M10 11.5h3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
            </svg>
            Compare
          </button>

          <div class="seg" role="group" aria-label="Language">
            <button type="button" :class="{ 'is-on': s.lang === 'en' }" @click="setLanguage('en')">EN</button>
            <button type="button" :class="{ 'is-on': s.lang === 'zh' }" @click="setLanguage('zh')">&#20013;&#25991;</button>
          </div>

          <button
            class="lab-btn lab-btn--icon"
            type="button"
            @click="toggleTheme"
            :aria-label="s.appearance === 'dark' ? 'Switch to light appearance' : 'Switch to dark appearance'"
          >
            <svg v-if="s.appearance === 'dark'" width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M13.2 9.6A5.4 5.4 0 0 1 6.4 2.8 5.6 5.6 0 1 0 13.2 9.6Z" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/>
            </svg>
            <svg v-else width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <circle cx="8" cy="8" r="3" stroke="currentColor" stroke-width="1.4"/>
              <path d="M8 1v1.5M8 13.5V15M1 8h1.5M13.5 8H15M3 3l1 1M12 12l1 1M13 3l-1 1M4 12l-1 1" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            </svg>
          </button>

          <button class="lab-btn lab-btn--accent" type="button" @click="s.tweaksOpen = !s.tweaksOpen">
            Tweaks <kbd>T</kbd>
          </button>
        </header>

        <main class="lab-main">
          <div class="stage-host" ref="host">
            <div class="panel" :style="panelStyle">
              <div class="panel__screen" :style="screenBox">
                <div class="screen-scaler" :style="scalerStyle">
                  <diplay-screen></diplay-screen>
                </div>
              </div>
            </div>
          </div>
        </main>

        <footer class="lab-foot">
          <span>1280 &times; 720 dp &nbsp;&middot;&nbsp; 1920 &times; 1080 px</span>
          <span class="lab-foot__hints"><b>T</b> tweaks &nbsp; <b>C</b> clean &nbsp; <b>V</b> compare</span>
          <span class="lab-foot__right">Scale {{ scalePercent }}%</span>
        </footer>

        <transition name="panel">
          <tweaks-panel v-if="s.tweaksOpen"></tweaks-panel>
        </transition>
      </div>
    `,
  });

  app.component('diplay-screen', window.DiplayScreen);
  app.component('glass-home', window.GlassHome);
  app.component('settings-view', window.SettingsView);
  app.component('settings-section', window.SettingsSection);
  app.component('phone-picker', window.PhonePicker);
  app.component('tweaks-panel', window.TweaksPanel);
  app.directive('ripple', ripple);
  app.mount('#app');
})();
