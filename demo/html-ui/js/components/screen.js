/* ============================================================
   <diplay-screen> — the 1280×720 dp head-unit surface.
   ============================================================ */

(function () {
  'use strict';

  const { computed, ref, onBeforeUnmount } = Vue;
  const s = DiPlay.store;

  const DiplayScreen = {
    name: 'DiplayScreen',

    setup() {
      const t = computed(() => DiPlay.copy());
      const root = ref(null);

      const accent = computed(() => DiPlay.accents[s.accent] || DiPlay.accents.lumos);

      const screenStyle = computed(() => {
        const dark = s.appearance === 'dark';
        const paint = accent.value;
        return {
          '--accent': dark ? paint.dark : paint.light,
          '--on-accent': dark ? paint.onDark : paint.onLight,
          '--corner': s.radius + 'px',
        };
      });

      const statusText = computed(() => {
        if (s.session === 'connecting') return t.value.connecting;
        if (s.session === 'connected') return t.value.connected;
        if (s.session === 'error') return t.value.failed;
        return t.value.ready;
      });

      const primaryText = computed(() => {
        if (s.session === 'connected') return t.value.open;
        if (s.session === 'connecting') return t.value.connecting;
        if (s.session === 'error') return t.value.retry;
        return t.value.connect;
      });

      const secondaryText = computed(() =>
        s.session === 'connected' ? t.value.disconnect : t.value.choose,
      );

      const warning = computed(
        () => s.session === 'error' || (s.scenario === 'hotspot-off' && s.session !== 'connected'),
      );

      const hintText = computed(() => {
        if (s.session === 'error') return t.value.failedHint;
        if (s.session === 'connected') {
          if (s.method === 'usb') return t.value.hintUsb;
          return DiPlay.format(t.value.hintConnected, {
            phone: s.phoneName,
            mode: t.value.modeWireless,
          });
        }
        if (s.scenario === 'hotspot-off') return t.value.hotspotOff;
        if (s.session === 'connecting') return t.value.hintConnecting;
        return t.value.hint1 + '\n' + t.value.hint2;
      });

      function onPrimary() {
        if (s.session === 'connecting') return;
        if (s.session === 'connected') {
          DiPlay.actions.toast(t.value.toastAlready);
          return;
        }
        DiPlay.actions.connect('wireless');
      }

      function onUsb() {
        if (s.session === 'connecting') return;
        if (s.session === 'connected') {
          DiPlay.actions.toast(t.value.toastAlready);
          return;
        }
        DiPlay.actions.connect('usb');
      }

      function onSecondary() {
        if (s.session === 'connected') DiPlay.actions.disconnect();
        else DiPlay.actions.openPhones();
      }

      function goCarHome() {
        DiPlay.actions.goCarHome();
      }

      function openSettings() {
        DiPlay.actions.openSettings();
      }

      // --- Reference compare: drag the divider to inspect the rebuild ---
      let dragging = false;

      function moveTo(clientX) {
        if (!root.value) return;
        const rect = root.value.getBoundingClientRect();
        const percent = ((clientX - rect.left) / rect.width) * 100;
        s.compareX = Math.min(98, Math.max(2, percent));
      }

      function onMove(event) {
        if (!dragging) return;
        moveTo(event.clientX);
      }

      function onUp() {
        dragging = false;
        window.removeEventListener('pointermove', onMove);
        window.removeEventListener('pointerup', onUp);
      }

      function startDrag(event) {
        event.preventDefault();
        dragging = true;
        moveTo(event.clientX);
        window.addEventListener('pointermove', onMove);
        window.addEventListener('pointerup', onUp);
      }

      onBeforeUnmount(onUp);

      return {
        s,
        t,
        root,
        screenStyle,
        statusText,
        primaryText,
        secondaryText,
        hintText,
        warning,
        onPrimary,
        onUsb,
        onSecondary,
        goCarHome,
        openSettings,
        startDrag,
        mark: './assets/carplay.png',
        reference: './assets/reference.png',
      };
    },

    template: `
      <div
        class="car-screen"
        ref="root"
        :class="{ motion: s.motion, 'no-motion': !s.motion, 'is-glass-home': s.view === 'home' && s.homeStyle === 'glass' }"
        :data-appearance="s.appearance"
        :data-lang="s.lang"
        :style="screenStyle"
      >
        <transition name="view" mode="out-in">
          <glass-home v-if="s.view === 'home' && s.homeStyle === 'glass'" key="glass"></glass-home>
          <div v-else-if="s.view === 'home'" key="home" class="screen__ui" :inert="s.sheet ? true : null">
          <header class="topbar">
            <img class="brand-mark" :src="mark" alt="" draggable="false">
            <span class="brand-name">{{ t.brand }}</span>
            <span class="topbar__spacer"></span>
            <button class="pill" type="button" v-ripple @click="goCarHome">
              {{ t.carHome }}
            </button>
          </header>

          <section class="hero">
            <div class="hero__copy">
              <p class="kicker">{{ t.kicker }}</p>
              <h1 class="hero__title">{{ t.title }}</h1>
              <p class="hero__lede">{{ t.body1 }}<br>{{ t.body2 }}</p>
            </div>
            <div class="hero__mark">
              <img :src="mark" alt="CarPlay" draggable="false">
            </div>
          </section>

          <section class="home-grid">
            <article class="card">
              <p class="card__eyebrow">{{ t.eyebrow }}</p>

              <h2 class="card__status">
                <span
                  v-if="s.session !== 'idle'"
                  class="status-dot"
                  :class="{ 'is-pending': s.session === 'connecting', 'is-error': s.session === 'error' }"
                ></span>
                {{ statusText }}
              </h2>

              <button
                class="btn btn--primary btn--lg"
                type="button"
                v-ripple
                :aria-disabled="s.session === 'connecting' ? 'true' : null"
                @click="onPrimary"
              >
                <span v-if="s.session === 'connecting'" class="spinner"></span>
                <span>{{ primaryText }}</span>
              </button>

              <p class="card__hint" :class="{ 'is-warning': warning }">{{ hintText }}</p>

              <button class="btn btn--secondary" type="button" v-ripple @click="onSecondary">
                {{ secondaryText }}
              </button>
            </article>

            <aside class="side">
              <button class="btn btn--secondary btn--lg" type="button" v-ripple @click="onUsb">
                {{ t.usb }}
              </button>
              <p class="side__note">{{ t.usbHint1 }}<br>{{ t.usbHint2 }}</p>

              <button class="btn btn--secondary btn--lg" type="button" v-ripple @click="openSettings">
                {{ t.settings }}
              </button>
              <p class="side__note side__note--make">{{ t.make }}</p>
              <p class="side__version">{{ t.preview }} &middot; {{ s.version }}</p>
            </aside>
          </section>
        </div>
          <settings-view v-else key="settings"></settings-view>
        </transition>

        <div v-if="s.grid" class="grid-overlay" aria-hidden="true">
          <div class="grid-overlay__safe"></div>
        </div>

        <div v-if="s.chrome && !(s.view === 'home' && s.homeStyle === 'glass')" class="gesture-bar" aria-hidden="true"></div>

        <div
          v-if="s.compare && !s.sheet && s.view === 'home' && s.homeStyle === 'replica'"
          class="compare-layer"
          :style="{ '--x': s.compareX + '%' }"
          @pointerdown="startDrag"
        >
          <img :src="reference" alt="Reference screenshot" draggable="false">
          <div class="compare-line"></div>
          <div class="compare-grip">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M6 3 2.5 8 6 13M10 3l3.5 5L10 13" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <span class="compare-tag compare-tag--left">{{ t.compareRef }}</span>
          <span class="compare-tag compare-tag--right">{{ t.compareLive }}</span>
        </div>

        <transition name="sheet">
          <phone-picker v-if="s.sheet === 'phones'"></phone-picker>
        </transition>

        <transition name="toast">
          <div v-if="s.toast" class="toast" role="status">
            <span class="toast__dot"></span>
            {{ s.toast }}
          </div>
        </transition>
      </div>
    `,
  };

  window.DiplayScreen = DiplayScreen;
})();
