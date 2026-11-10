/* ============================================================
   <settings-view> — glass settings shell: sky, glass rail, glass cards.
   ============================================================ */

(function () {
  'use strict';

  const { computed, ref, watch, nextTick, onMounted, onBeforeUnmount } = Vue;
  const s = DiPlay.store;

  const SettingsView = {
    name: 'SettingsView',

    setup() {
      const t = computed(() => DiPlay.copy());
      const data = window.DiPlaySettingsData;
      const categories = data.categories;
      const pane = ref(null);
      const searchInput = ref(null);
      const choice = ref(null);
      const choiceModal = ref(null);

      const label = (value) => (value ? value[s.lang] || value.en : '');

      const category = computed(() => data.getCategory(s.settingsCategory));

      const activeIndex = computed(() => {
        const index = categories.findIndex((item) => item.id === s.settingsCategory);
        return index < 0 ? 0 : index;
      });

      const rail = ref(null);
      let railStartY = 0;
      let railStartTop = 0;
      let railActive = false;
      let railMoved = false;
      let suppressRailClick = false;

      function beginRailDrag(y) {
        if (!rail.value) return;
        railActive = true;
        railMoved = false;
        railStartY = y;
        railStartTop = rail.value.scrollTop;
      }

      function moveRail(y) {
        if (!railActive || !rail.value) return;
        const dy = y - railStartY;
        if (!railMoved && Math.abs(dy) > 5) railMoved = true;
        if (!railMoved) return;

        if (rail.value.scrollHeight > rail.value.clientHeight + 2) {
          rail.value.scrollTop = railStartTop - dy;
          return;
        }

        // The seven categories fit: dragging the control walks the selection.
        const rect = rail.value.getBoundingClientRect();
        const index = Math.floor((y - rect.top - 12 + rail.value.scrollTop) / 80);
        const item = categories[index];
        if (item && item.id !== s.settingsCategory) {
          DiPlay.actions.setCategory(item.id);
        }
      }

      function endRailDrag() {
        if (!railActive) return;
        railActive = false;
        window.removeEventListener('pointermove', onRailMove);
        window.removeEventListener('pointerup', endRailDrag);
        window.removeEventListener('pointercancel', endRailDrag);
        window.removeEventListener('touchmove', onRailTouchMove);
        window.removeEventListener('touchend', endRailDrag);
        window.removeEventListener('touchcancel', endRailDrag);
        if (railMoved) {
          suppressRailClick = true;
          window.setTimeout(() => {
            suppressRailClick = false;
          }, 320);
        }
      }

      function onRailDown(event) {
        if (event.pointerType && event.pointerType !== 'mouse') return;
        if (event.button !== undefined && event.button !== 0) return;
        beginRailDrag(event.clientY);
        window.addEventListener('pointermove', onRailMove);
        window.addEventListener('pointerup', endRailDrag);
        window.addEventListener('pointercancel', endRailDrag);
      }

      function onRailMove(event) {
        moveRail(event.clientY);
        if (railMoved) event.preventDefault();
      }

      function onRailTouchStart(event) {
        if (event.touches.length !== 1) return;
        beginRailDrag(event.touches[0].clientY);
        window.addEventListener('touchmove', onRailTouchMove, { passive: false });
        window.addEventListener('touchend', endRailDrag);
        window.addEventListener('touchcancel', endRailDrag);
      }

      function onRailTouchMove(event) {
        if (!railActive || !event.touches.length) return;
        moveRail(event.touches[0].clientY);
        if (railMoved) event.preventDefault();
      }

      // --- Search index -------------------------------------------------
      const index = computed(() => {
        const items = [];
        for (const item of categories) {
          for (const section of item.sections) {
            for (const control of section.controls || []) {
              if (!control.title) continue;
              items.push({
                kind: 'setting',
                key: item.id + ':' + (control.key || control.id),
                settingId: control.key || control.id,
                control,
                categoryId: item.id,
                title: label(control.title),
                desc: label(control.desc),
                sub: label(item.title) + ' \u00b7 ' + label(section.title),
              });
            }
          }
        }
        return items;
      });

      const results = computed(() => {
        const query = s.settingsQuery.trim().toLowerCase();
        if (!query) {
          return categories.map((item) => ({
            kind: 'category',
            key: 'cat-' + item.id,
            categoryId: item.id,
            title: label(item.title),
            sub: label(item.summary),
          }));
        }
        return index.value
          .filter((item) =>
            (item.title + ' ' + item.desc + ' ' + item.sub).toLowerCase().includes(query),
          )
          .slice(0, 9);
      });

      function openCategory(id) {
        if (suppressRailClick) return;
        choice.value = null;
        DiPlay.actions.setCategory(id);
      }

      function openChoice(control) {
        choice.value = {
          control,
          title: label(control.title),
          options: control.options || [],
          value: DiPlay.actions.getPref(control.key, control.scope),
        };
        nextTick(() => {
          if (!choiceModal.value) return;
          const selected =
            choiceModal.value.querySelector('.choice-row.is-on') ||
            choiceModal.value.querySelector('.choice-row');
          if (selected) selected.focus();
        });
      }

      function pick(option) {
        const control = choice.value && choice.value.control;
        choice.value = null;
        if (control) DiPlay.actions.setPref(control.key, option.value, control.scope);
      }

      function run(control) {
        DiPlay.actions.runSettingsAction(control.id);
      }

      function jumpTo(result) {
        if (result.kind === 'category') {
          DiPlay.actions.setCategory(result.categoryId);
          DiPlay.actions.closeSearch();
          return;
        }
        DiPlay.actions.setCategory(result.categoryId);
        DiPlay.actions.closeSearch();
        nextTick(() => {
          DiPlay.actions.highlightSetting(result.settingId);
          const host = pane.value;
          if (!host) return;
          const node = host.querySelector('[data-setting="' + result.settingId + '"]');
          if (!node) return;
          host.scrollTop += node.getBoundingClientRect().top - host.getBoundingClientRect().top - 120;
        });
      }

      watch(
        () => s.settingsCategory,
        () => {
          if (pane.value) pane.value.scrollTop = 0;
        },
      );

      watch(
        () => s.view,
        (view) => {
          if (view === 'settings' && pane.value) pane.value.scrollTop = 0;
        },
      );

      watch(
        () => s.settingsSearch,
        (open) => {
          if (open) nextTick(() => searchInput.value && searchInput.value.focus());
        },
      );

      function onEscapeCapture(event) {
        if (event.key !== 'Escape') return;
        if (choice.value) {
          event.stopPropagation();
          choice.value = null;
        } else if (s.settingsSearch) {
          event.stopPropagation();
          DiPlay.actions.closeSearch();
        }
      }

      onMounted(() => {
        if (pane.value) pane.value.scrollTop = 0;
        document.addEventListener('keydown', onEscapeCapture, true);

        // Deep links for screenshots and demos: ?choice=appearance&search=night
        const params = new URLSearchParams(window.location.search);
        const choiceKey = params.get('choice');
        if (choiceKey) {
          const hit = index.value.find((item) => item.settingId === choiceKey);
          if (hit) openChoice(hit.control);
        }
        const query = params.get('search');
        if (query) {
          DiPlay.actions.openSearch();
          s.settingsQuery = query;
        }
      });

      onBeforeUnmount(() => {
        endRailDrag();
        document.removeEventListener('keydown', onEscapeCapture, true);
      });

      return {
        s,
        t,
        categories,
        category,
        activeIndex,
        rail,
        onRailDown,
        onRailTouchStart,
        pane,
        searchInput,
        choice,
        choiceModal,
        results,
        label,
        openCategory,
        openChoice,
        pick,
        run,
        jumpTo,
        back: () => DiPlay.actions.closeSettings(),
        openSearch: () => DiPlay.actions.openSearch(),
        closeSearch: () => DiPlay.actions.closeSearch(),
      };
    },

    template: `
      <div class="settings" :inert="s.sheet ? true : null">
        <div class="gh-sky settings-sky" aria-hidden="true">
          <span class="gh-sky__wash gh-sky__wash--cool"></span>
          <span class="gh-sky__wash gh-sky__wash--warm"></span>
          <span class="gh-sky__streak gh-sky__streak--top"></span>
          <span class="gh-sky__streak gh-sky__streak--bottom"></span>
          <span class="gh-sky__grain"></span>
        </div>

        <div class="settings__scrim" aria-hidden="true"></div>

        <header class="settings__topbar" :inert="choice || s.settingsSearch ? true : null">
          <button class="s-back" type="button" :aria-label="t.back" @click="back">
            <svg aria-hidden="true"><use href="#i-back"></use></svg>
          </button>
          <h1 class="settings__title">{{ t.settings }}</h1>
          <span class="settings__spacer"></span>
          <button class="s-iconbtn" type="button" @click="openSearch" :aria-label="t.search">
            <svg aria-hidden="true"><use href="#i-search"></use></svg>
          </button>
        </header>

        <nav
          class="rail"
          ref="rail"
          :aria-label="t.settings"
          :inert="choice || s.settingsSearch ? true : null"
          @pointerdown="onRailDown"
          @touchstart="onRailTouchStart"
        >
          <span
            class="rail__pill"
            aria-hidden="true"
            :style="{ transform: 'translateY(' + (12 + activeIndex * 80) + 'px)' }"
          ></span>
          <button
            v-for="item in categories"
            :key="item.id"
            type="button"
            class="rail__item"
            :class="{ 'is-on': s.settingsCategory === item.id }"
            :aria-current="s.settingsCategory === item.id ? 'page' : null"
            @click="openCategory(item.id)"
          >
            <svg class="rail__icon" aria-hidden="true"><use :href="'#i-' + item.icon"></use></svg>
            <span class="rail__label">{{ label(item.title) }}</span>
          </button>
        </nav>

        <div class="settings__scroll" ref="pane" :inert="choice || s.settingsSearch ? true : null">
          <div class="page-head">
            <h2 class="page-head__title">{{ label(category.title) }}</h2>
            <p class="page-head__sub">{{ label(category.summary) }}</p>
          </div>

          <settings-section
            v-for="section in category.sections"
            :key="section.id"
            :section="section"
            :highlight="s.settingsHighlight"
            @choose="openChoice"
            @run="run"
          ></settings-section>
        </div>

        <transition name="sheet">
          <div v-if="choice" class="modal-scrim" @click.self="choice = null">
            <div class="choice-modal" ref="choiceModal" role="dialog" aria-modal="true" :aria-label="choice.title">
              <h2 class="choice-modal__title">{{ choice.title }}</h2>
              <div class="choice-list">
                <button
                  v-for="option in choice.options"
                  :key="option.value"
                  type="button"
                  class="choice-row"
                  :class="{ 'is-on': option.value === choice.value }"
                  @click="pick(option)"
                >
                  <span>{{ label(option.label) }}</span>
                  <span class="radio" aria-hidden="true">
                    <span v-if="option.value === choice.value" class="radio__dot"></span>
                  </span>
                </button>
              </div>
              <button class="choice-cancel" type="button" @click="choice = null">{{ t.cancel }}</button>
            </div>
          </div>
        </transition>

        <transition name="sheet">
          <div v-if="s.settingsSearch" class="modal-scrim search-scrim" @click.self="closeSearch">
            <div class="search-panel" role="dialog" aria-modal="true" :aria-label="t.searchPlaceholder">
              <div class="search-box">
                <svg class="search-box__icon" aria-hidden="true"><use href="#i-search"></use></svg>
                <input
                  ref="searchInput"
                  v-model="s.settingsQuery"
                  type="search"
                  :placeholder="t.searchPlaceholder"
                  :aria-label="t.searchPlaceholder"
                  autocomplete="off"
                  spellcheck="false"
                  @keydown.esc.stop="closeSearch"
                >
                <button class="search-close" type="button" @click="closeSearch" :aria-label="t.cancel">
                  <svg aria-hidden="true"><use href="#i-close"></use></svg>
                </button>
              </div>

              <p class="search-label">{{ s.settingsQuery ? t.search : t.searchJump }}</p>

              <div class="search-results">
                <button
                  v-for="result in results"
                  :key="result.key"
                  type="button"
                  class="search-result"
                  @click="jumpTo(result)"
                >
                  <span class="search-result__text">
                    <strong>{{ result.title }}</strong>
                    <small>{{ result.sub }}</small>
                  </span>
                  <svg class="search-result__chev" aria-hidden="true"><use href="#i-chevron"></use></svg>
                </button>
                <p v-if="!results.length" class="search-empty">
                  {{ t.searchEmpty.replace('{q}', s.settingsQuery) }}
                </p>
              </div>
            </div>
          </div>
        </transition>
      </div>
    `,
  };

  window.SettingsView = SettingsView;
})();

