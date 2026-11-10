/* ============================================================
   <tweaks-panel> — live design parameters for the lab (right drawer).
   ============================================================ */

(function () {
  'use strict';

  const { computed } = Vue;
  const s = DiPlay.store;

  const TweaksPanel = {
    name: 'TweaksPanel',

    setup() {
      const accents = DiPlay.accents;

      const scenario = computed(() => {
        if (s.session === 'connected') return 'connected';
        if (s.session === 'error') return 'error';
        if (s.scenario === 'hotspot-off') return 'hotspot';
        return 'idle';
      });

      function toggle(key) {
        s[key] = !s[key];
      }

      function toggleChrome() {
        DiPlay.actions.setFullScreen(s.chrome);
      }

      return {
        s,
        accents,
        accentKeys: Object.keys(accents),
        scenario,
        toggle,
        toggleChrome,
        setAppearance: (mode) => DiPlay.actions.setAppearance(mode),
        setLanguage: (mode) => DiPlay.actions.setLanguage(mode),
        setHomeStyle: (style) => DiPlay.actions.setHomeStyle(style),
        setScenario: (value) => DiPlay.actions.setScenario(value),
        reset: () => DiPlay.actions.reset(),
      };
    },

    template: `
      <div class="tweaks" role="dialog" aria-label="Tweaks">
        <header class="tweaks__head">
          <span class="tweaks__title">Tweaks</span>
          <button class="tweaks__close" type="button" @click="s.tweaksOpen = false" aria-label="Close tweaks">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
              <path d="M2 2l8 8M10 2l-8 8" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/>
            </svg>
          </button>
        </header>

        <section class="tweaks__group">
          <p class="tweaks__label">Home</p>
          <div class="seg seg--wide">
            <button type="button" :class="{ 'is-on': s.homeStyle === 'glass' }" @click="setHomeStyle('glass')">Glass</button>
            <button type="button" :class="{ 'is-on': s.homeStyle === 'replica' }" @click="setHomeStyle('replica')">Replica</button>
          </div>
        </section>

        <section class="tweaks__group">
          <p class="tweaks__label">Appearance</p>
          <div class="seg seg--wide">
            <button type="button" :class="{ 'is-on': s.appearance === 'dark' }" @click="setAppearance('dark')">Dark</button>
            <button type="button" :class="{ 'is-on': s.appearance === 'light' }" @click="setAppearance('light')">Light</button>
          </div>
        </section>

        <section class="tweaks__group">
          <p class="tweaks__label">Language</p>
          <div class="seg seg--wide">
            <button type="button" :class="{ 'is-on': s.lang === 'en' }" @click="setLanguage('en')">English</button>
            <button type="button" :class="{ 'is-on': s.lang === 'zh' }" @click="setLanguage('zh')">\u4e2d\u6587</button>
          </div>
        </section>

        <section class="tweaks__group">
          <p class="tweaks__label">
            Accent
            <span class="tweaks__value">{{ accents[s.accent].name }}</span>
          </p>
          <div class="swatches">
            <button
              v-for="key in accentKeys"
              :key="key"
              class="swatch"
              type="button"
              :class="{ 'is-on': s.accent === key }"
              :style="{ '--sw': s.appearance === 'dark' ? accents[key].dark : accents[key].light }"
              :aria-label="accents[key].name"
              @click="s.accent = key"
            ></button>
          </div>
        </section>

        <section class="tweaks__group">
          <p class="tweaks__label">
            Corners
            <span class="tweaks__value">{{ s.radius }} dp</span>
          </p>
          <input type="range" min="8" max="28" step="2" v-model.number="s.radius" aria-label="Corner radius">
        </section>

        <section class="tweaks__group">
          <p class="tweaks__label">Scenario</p>
          <div class="tweaks__grid">
            <button type="button" class="tweaks__choice" :class="{ 'is-on': scenario === 'idle' }" @click="setScenario('idle')">Idle</button>
            <button type="button" class="tweaks__choice" :class="{ 'is-on': scenario === 'connected' }" @click="setScenario('connected')">Connected</button>
            <button type="button" class="tweaks__choice" :class="{ 'is-on': scenario === 'hotspot' }" @click="setScenario('hotspot')">Wi-Fi off</button>
            <button type="button" class="tweaks__choice" :class="{ 'is-on': scenario === 'error' }" @click="setScenario('error')">Error</button>
          </div>
        </section>

        <section class="tweaks__group">
          <p class="tweaks__label">Canvas</p>
          <div class="tweaks__row">
            <span>Gesture bar</span>
            <button type="button" class="switch" :class="{ 'is-on': s.chrome }" :aria-pressed="s.chrome" @click="toggleChrome" aria-label="Gesture bar"></button>
          </div>
          <div class="tweaks__row">
            <span>8 dp grid</span>
            <button type="button" class="switch" :class="{ 'is-on': s.grid }" :aria-pressed="s.grid" @click="toggle('grid')" aria-label="Grid overlay"></button>
          </div>
          <div class="tweaks__row">
            <span>Motion</span>
            <button type="button" class="switch" :class="{ 'is-on': s.motion }" :aria-pressed="s.motion" @click="toggle('motion')" aria-label="Motion"></button>
          </div>
          <div class="tweaks__row">
            <span>Compare reference</span>
            <button type="button" class="switch" :class="{ 'is-on': s.compare }" :aria-pressed="s.compare" @click="toggle('compare')" aria-label="Compare with reference"></button>
          </div>
          <div class="tweaks__row">
            <span>Presentation</span>
            <button type="button" class="switch" :class="{ 'is-on': s.clean }" :aria-pressed="s.clean" @click="toggle('clean')" aria-label="Presentation mode"></button>
          </div>
        </section>

        <footer class="tweaks__foot">
          <button type="button" class="tweaks__reset" @click="reset">Reset</button>
          <button type="button" class="tweaks__done" @click="s.tweaksOpen = false">Done</button>
        </footer>
      </div>
    `,
  };

  window.TweaksPanel = TweaksPanel;
})();
