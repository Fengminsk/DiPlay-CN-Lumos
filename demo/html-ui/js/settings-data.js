/* ============================================================
   Settings tree for the lab.

   Information architecture:
     General (shared)  — appearance, picture and sound used by both protocols
     CarPlay           — CarPlay-only connection, picture, controls and navigation
     ICCOA             — ICCOA CarLink-only connection, projection, microphone, touch

   Every stored control carries a scope: "shared" | "carplay" | "iccoa".
   ICCOA keys follow the CarLink settings in MiAuto (resolution, codec, DPI, mic).
   ============================================================ */

(function () {
  'use strict';

  const L = (en, zh) => ({ en, zh });
  const O = (en, zh, value) => ({ label: L(en, zh), value });

  const DEFAULTS = {
    shared: {
      appearance: 'dark',
      fullScreen: false,
      interfaceSize: 0,
      bufferedAudio: false,
      buffer: 0,
      echoCancel: false,
      siriKey: 0,
      joystick: true,
      carButton: true,
      cloudUpload: false,
      splitScreen: false,
      rotation: true,
      sidePanel: false,
      smoothVideo: false,
      channelMapping: false,
      language: 'en',
    },
    carplay: {
      wireless: true,
      autoConnect: true,
      btConnect: false,
      defaultMode: 0,
      nightMode: 0,
      resolution: 0,
      frameRate: 0,
      efficientVideo: false,
      pip: true,
      carBtAudio: false,
      gestureFingers: 0,
      swipeTarget: 0,
      launcherReturns: false,
      rightHand: false,
      location: true,
      clusterMap: false,
      clusterHold: 0,
    },
    iccoa: {
      iccoaWireless: true,
      iccoaUsb: false,
      iccoaBle: true,
      iccoaAutoConnect: true,
      iccoaResolution: 2,
      iccoaCodec: 'Auto',
      iccoaDpi: 0,
      iccoaStretch: false,
      iccoaMicRate: 16000,
      iccoaMicEcho: false,
      iccoaMicNoise: false,
      iccoaTouch: true,
      iccoaHud: false,
    },
  };

  const categories = [
    {
      id: 'general',
      scope: 'shared',
      icon: 'gear',
      title: L('General', '通用'),
      summary: L(
        'Picture, sound and appearance shared by CarPlay and ICCOA.',
        'CarPlay 与 ICCOA 共用的画面、声音与外观。',
      ),
      sections: [
        {
          id: 'display-appearance',
          icon: 'display',
          title: L('Display and appearance', '显示与外观'),
          controls: [
            {
              type: 'choice',
              key: 'appearance',
              title: L('App appearance', '应用外观'),
              options: [
                O('Dark', '深色', 'dark'),
                O('Light', '浅色', 'light'),
                O('Auto', '跟随系统', 'auto'),
              ],
            },
            {
              type: 'toggle',
              key: 'fullScreen',
              title: L('Full screen', '全屏显示'),
              desc: L(
                'Hide the car’s system bars while a session is open.',
                '连接期间隐藏车机系统栏。',
              ),
            },
            {
              type: 'choice',
              key: 'interfaceSize',
              title: L('Interface size', '界面大小'),
              desc: L('Only DiPlay’s own controls change size.', '只调整 DiPlay 自身控件的大小。'),
              options: [O('Default', '默认', 0), O('125%', '125%', 1), O('150%', '150%', 2)],
            },
          ],
        },
        {
          id: 'sound',
          icon: 'audio',
          title: L('Sound', '声音'),
          controls: [
            {
              type: 'toggle',
              key: 'bufferedAudio',
              title: L('Main buffered audio', '主音频缓冲'),
              desc: L('Adds a small buffer before playback.', '播放前加入一小段缓冲。'),
            },
            {
              type: 'choice',
              key: 'buffer',
              title: L('Music buffer', '音乐缓冲'),
              options: [
                O('300 ms · default', '300 ms · 默认', 0),
                O('500 ms', '500 ms', 1),
                O('1000 ms · most stable', '1000 ms · 最稳定', 2),
              ],
            },
            {
              type: 'toggle',
              key: 'echoCancel',
              title: L('Call echo cancellation', '通话回声消除'),
              desc: L('Reduce echo on hands-free calls.', '降低免提通话的回声。'),
            },
          ],
        },
        {
          id: 'picture-shared',
          icon: 'picture',
          title: L('Picture', '画面'),
          note: L(
            'Applies to the projection surface used by both protocols.',
            '同时作用于两种协议的投屏画面。',
          ),
          controls: [
            { type: 'action', id: 'picture', title: L('Picture adjustments', '画面调整') },
          ],
        },
        {
          id: 'language',
          icon: 'language',
          title: L('Language', '语言'),
          controls: [
            {
              type: 'choice',
              key: 'language',
              title: L('Language', '语言'),
              options: [
                O('English', 'English', 'en'),
                O('中文', '中文', 'zh'),
                O('Follow system', '跟随系统', 'system'),
              ],
            },
            {
              type: 'note',
              text: L('This lab applies the language immediately.', '本 UI Lab 会立即切换语言。'),
            },
          ],
        },
      ],
    },
    {
      id: 'carplay',
      scope: 'carplay',
      icon: 'carplay',
      title: L('CarPlay', 'CarPlay'),
      summary: L('Connection, picture, controls and navigation for CarPlay.', 'CarPlay 的连接、画面、操作与导航。'),
      sections: [
        {
          id: 'carplay-connection',
          icon: 'connection',
          title: L('Connection', '连接'),
          controls: [
            {
              type: 'toggle',
              key: 'wireless',
              title: L('Wireless CarPlay', '无线 CarPlay'),
              desc: L(
                'Connect over the car’s Wi-Fi or Wi-Fi Direct.',
                '通过车机 Wi-Fi 或 Wi-Fi Direct 连接。',
              ),
            },
            { type: 'phone', id: 'choosePhone', title: L('Choose iPhone', '选择 iPhone') },
            {
              type: 'toggle',
              key: 'autoConnect',
              title: L('Connect when DiPlay opens', '打开 DiPlay 时连接'),
              desc: L('Use the default mode every time.', '每次启动都使用默认连接方式。'),
            },
            {
              type: 'toggle',
              key: 'btConnect',
              title: L('Connect when iPhone Bluetooth connects', 'iPhone 蓝牙连接时自动连接'),
              desc: L('Start CarPlay as soon as your iPhone joins the car.', 'iPhone 连上车机后立即启动 CarPlay。'),
            },
            {
              type: 'choice',
              key: 'defaultMode',
              title: L('Default connection mode', '默认连接方式'),
              options: [O('Last used', '上次使用', 0), O('Wireless', '无线', 1), O('USB', 'USB', 2)],
            },
          ],
        },
        {
          id: 'carplay-picture',
          icon: 'display',
          title: L('Picture', '画面'),
          controls: [
            {
              type: 'choice',
              key: 'nightMode',
              title: L('CarPlay night mode', 'CarPlay 夜间模式'),
              options: [
                O('System', '跟随系统', 0),
                O('Ambient light', '环境光', 1),
                O('Day', '白天', 2),
                O('Night', '夜间', 3),
                O('Schedule', '定时', 4),
              ],
            },
            {
              type: 'choice',
              key: 'resolution',
              title: L('Resolution', '分辨率'),
              desc: L('Applies the next time CarPlay connects.', '下次连接 CarPlay 时生效。'),
              options: [O('Native', '原生', 0), O('90%', '90%', 1), O('80%', '80%', 2), O('75%', '75%', 3)],
            },
            {
              type: 'choice',
              key: 'frameRate',
              title: L('Frame rate', '帧率'),
              options: [
                O('30 fps · lighter load', '30 fps · 更省性能', 0),
                O('60 fps · smoother motion', '60 fps · 更流畅', 1),
              ],
            },
            {
              type: 'toggle',
              key: 'efficientVideo',
              title: L('Efficient video (HEVC)', '高效视频（HEVC）'),
              desc: L('Leave off for the widest head-unit compatibility.', '关闭可获得最广的车机兼容性。'),
            },
            {
              type: 'toggle',
              key: 'pip',
              title: L('Adapt PIP resolution', '适配画中画分辨率'),
              desc: L('Match picture-in-picture to the head unit.', '让画中画分辨率与车机匹配。'),
            },
          ],
        },
        {
          id: 'carplay-controls',
          icon: 'controls',
          title: L('Controls', '操作'),
          controls: [
            {
              type: 'choice',
              key: 'gestureFingers',
              title: L('Gesture fingers', '手势手指数'),
              options: [O('2 fingers', '2 指', 0), O('3 fingers', '3 指', 1), O('4 fingers', '4 指', 2)],
            },
            {
              type: 'choice',
              key: 'swipeTarget',
              title: L('Swipe opens', '滑动打开'),
              options: [O('Overlay', '浮层', 0), O('Full settings', '完整设置', 1)],
            },
            {
              type: 'toggle',
              key: 'launcherReturns',
              title: L('Launcher returns to CarPlay', '桌面返回 CarPlay'),
              desc: L('Reopen the session instead of the home screen.', '再次打开时直接回到 CarPlay，而不是首页。'),
            },
            {
              type: 'toggle',
              key: 'rightHand',
              title: L('Right-hand drive', '右舵驾驶'),
              desc: L('Place CarPlay’s controls closer to the driver.', '把 CarPlay 控件放在更靠近驾驶员的一侧。'),
            },
          ],
        },
        {
          id: 'carplay-navigation',
          icon: 'navigation',
          title: L('Navigation', '导航'),
          controls: [
            {
              type: 'toggle',
              key: 'location',
              title: L('Report location to iPhone', '向 iPhone 报告位置'),
              desc: L(
                'Send precise Android location as CarPlay GPS data.',
                '将 Android 精确定位作为 CarPlay GPS 数据发送。',
              ),
            },
            {
              type: 'toggle',
              key: 'clusterMap',
              title: L('CarPlay map on instrument cluster (experimental)', '仪表盘显示 CarPlay 地图（实验性）'),
              desc: L('Needs ADB access and a supported cluster.', '需要 ADB 权限与受支持的仪表。'),
            },
            {
              type: 'choice',
              key: 'clusterHold',
              title: L('OEM cluster map', '原厂仪表地图'),
              options: [O('Off', '关闭', 0), O('Navigation', '导航', 1), O('Media', '媒体', 2)],
            },
            {
              type: 'toggle',
              key: 'carBtAudio',
              title: L('Car Bluetooth audio (experimental)', '车机蓝牙音频（实验性）'),
              desc: L('Route audio through the car instead of CarPlay.', '改用车辆蓝牙输出，而不是 CarPlay 音频。'),
            },
          ],
        },
      ],
    },
    {
      id: 'iccoa',
      scope: 'iccoa',
      icon: 'link',
      title: L('ICCOA', 'ICCOA'),
      summary: L('Connection, projection and microphone for ICCOA CarLink.', 'ICCOA CarLink 的连接、投屏与麦克风。'),
      sections: [
        {
          id: 'iccoa-connection',
          icon: 'connection',
          title: L('Connection', '连接'),
          controls: [
            {
              type: 'toggle',
              key: 'iccoaWireless',
              title: L('Wireless projection', '无线投屏'),
              desc: L('Join over Wi-Fi and start from the phone.', '通过 Wi-Fi 连接，并从手机发起投屏。'),
            },
            {
              type: 'toggle',
              key: 'iccoaUsb',
              title: L('USB projection', 'USB 投屏'),
              desc: L('Keep the phone charged while projecting.', '投屏时同时给手机充电。'),
            },
            {
              type: 'toggle',
              key: 'iccoaBle',
              title: L('BLE fallback advertising', 'BLE 兜底广播'),
              desc: L('Stay discoverable when Wi-Fi discovery fails.', 'Wi-Fi 发现失败时仍可被手机发现。'),
            },
            {
              type: 'toggle',
              key: 'iccoaAutoConnect',
              title: L('Connect when DiPlay opens', '打开 DiPlay 时连接'),
              desc: L('Start ICCOA with the last used phone.', '使用上次的手机启动 ICCOA。'),
            },
          ],
        },
        {
          id: 'iccoa-projection',
          icon: 'display',
          title: L('Projection', '投屏'),
          controls: [
            {
              type: 'choice',
              key: 'iccoaResolution',
              title: L('Resolution', '分辨率'),
              options: [
                O('480p', '480p', 1),
                O('720p', '720p', 2),
                O('1080p', '1080p', 3),
                O('1440p (2K)', '1440p（2K）', 4),
                O('2160p (4K)', '2160p（4K）', 5),
              ],
            },
            {
              type: 'choice',
              key: 'iccoaCodec',
              title: L('Video codec', '视频编码'),
              desc: L('Auto lets the phone and the car negotiate.', '自动模式下由手机与车机协商。'),
              options: [O('Auto', '自动', 'Auto'), O('H.264', 'H.264', 'H.264'), O('H.265', 'H.265', 'H.265')],
            },
            {
              type: 'choice',
              key: 'iccoaDpi',
              title: L('Pixel density', '像素密度'),
              options: [
                O('Auto', '自动', 0),
                O('160 dpi', '160 dpi', 160),
                O('240 dpi', '240 dpi', 240),
                O('320 dpi', '320 dpi', 320),
                O('480 dpi', '480 dpi', 480),
              ],
            },
            {
              type: 'toggle',
              key: 'iccoaStretch',
              title: L('Stretch to fill', '拉伸填满'),
              desc: L('Fill the screen instead of keeping the phone’s ratio.', '填满屏幕，而不是保持手机画面比例。'),
            },
          ],
        },
        {
          id: 'iccoa-microphone',
          icon: 'mic',
          title: L('Microphone', '麦克风'),
          controls: [
            {
              type: 'choice',
              key: 'iccoaMicRate',
              title: L('Sample rate', '采样率'),
              options: [
                O('8 kHz', '8 kHz', 8000),
                O('16 kHz · default', '16 kHz · 默认', 16000),
                O('24 kHz', '24 kHz', 24000),
                O('32 kHz', '32 kHz', 32000),
                O('44.1 kHz', '44.1 kHz', 44100),
                O('48 kHz', '48 kHz', 48000),
              ],
            },
            {
              type: 'toggle',
              key: 'iccoaMicEcho',
              title: L('Echo cancellation', '回声消除'),
              desc: L('Reduce echo picked up by the car microphone.', '降低车机麦克风拾取的回声。'),
            },
            {
              type: 'toggle',
              key: 'iccoaMicNoise',
              title: L('Noise suppression', '降噪'),
              desc: L('Soften road and fan noise.', '削弱路噪与风噪。'),
            },
          ],
        },
        {
          id: 'iccoa-touch',
          icon: 'touch',
          title: L('Touch and cluster', '触控与仪表'),
          controls: [
            {
              type: 'toggle',
              key: 'iccoaTouch',
              title: L('Measured touch surface', '实测触摸区域'),
              desc: L('Map touches to the measured area of the display.', '把触摸映射到实测的显示区域。'),
            },
            {
              type: 'toggle',
              key: 'iccoaHud',
              title: L('HUD mirroring', 'HUD 镜像'),
              desc: L('Mirror the projection to a supported HUD display.', '把投屏镜像到受支持的 HUD 显示。'),
            },
          ],
        },
      ],
    },
    {
      id: 'vehicle',
      scope: 'shared',
      icon: 'car',
      title: L('Vehicle', '车辆'),
      summary: L('Car button and wheel controls for this head unit.', '本车机的车辆按钮与方向盘控制。'),
      sections: [
        {
          id: 'car-button',
          icon: 'car',
          title: L('Car button', '车辆按钮'),
          controls: [
            {
              type: 'toggle',
              key: 'carButton',
              title: L('Show car button', '显示车辆按钮'),
              desc: L('Keep a way back to the car’s own apps.', '保留返回车机应用的入口。'),
            },
            { type: 'action', id: 'carButtonImage', title: L('Choose button image', '选择按钮图片') },
          ],
        },
        {
          id: 'wheel-keys',
          icon: 'wheel',
          title: L('Wheel and joystick', '方向盘与摇杆'),
          controls: [
            {
              type: 'choice',
              key: 'siriKey',
              title: L('Voice key', '语音键'),
              desc: L('Calls Siri while CarPlay is connected.', 'CarPlay 连接时唤起 Siri。'),
              options: [O('Long press', '长按', 0), O('Double press', '双击', 1), O('Off', '关闭', 2)],
            },
            {
              type: 'toggle',
              key: 'joystick',
              title: L('Joystick controls', '摇杆控制'),
              desc: L('Use the BYD joystick to move focus.', '使用比亚迪摇杆移动焦点。'),
            },
          ],
        },
      ],
    },
    {
      id: 'diagnostics',
      scope: 'shared',
      icon: 'diagnostics',
      title: L('Diagnostics', '诊断'),
      summary: L('Reports and logs that help explain a problem', '用于排查问题的报告与日志'),
      sections: [
        {
          id: 'report',
          icon: 'diagnostics',
          title: L('Diagnostic report', '诊断报告'),
          controls: [
            { type: 'action', id: 'saveReport', title: L('Save diagnostic report', '保存诊断报告') },
            { type: 'action', id: 'saveLocation', title: L('Choose save location', '选择保存位置') },
            { type: 'note', text: L('Reports save to Downloads/DiPlay.', '报告保存到 Downloads/DiPlay。') },
          ],
        },
        {
          id: 'cloud-logs',
          icon: 'cloud',
          title: L('Cloud logs', '云端日志'),
          controls: [
            {
              type: 'toggle',
              key: 'cloudUpload',
              title: L('Upload cloud logs', '上传云端日志'),
              desc: L('Optional. Only runs after you configure it.', '可选。仅在你完成配置后运行。'),
            },
            { type: 'action', id: 'configureCloud', title: L('Configure cloud logs', '配置云端日志') },
          ],
        },
      ],
    },
    {
      id: 'advanced',
      scope: 'shared',
      icon: 'advanced',
      title: L('Advanced', '高级'),
      summary: L('Experimental and vehicle-specific controls', '实验性与车机专属控制'),
      sections: [
        {
          id: 'display',
          icon: 'display',
          title: L('Display', '显示'),
          controls: [
            {
              type: 'choice',
              key: 'splitScreen',
              title: L('Split screen areas', '分屏区域'),
              desc: L('Run a session beside the car’s own apps.', '让投屏与车机应用并排显示。'),
              options: [O('Off', '关闭', false), O('On', '开启', true)],
            },
            {
              type: 'toggle',
              key: 'rotation',
              title: L('Rotation support', '随屏幕旋转'),
              desc: L('Rotate without reconnecting.', '旋转屏幕时无需重新连接。'),
            },
            {
              type: 'toggle',
              key: 'sidePanel',
              title: L('Side panel (experimental)', '侧边面板（实验性）'),
              desc: L('Reserve a strip for the car’s controls.', '为车机控件保留一条侧边区域。'),
            },
          ],
        },
        {
          id: 'advanced-media',
          icon: 'media',
          title: L('Video and audio', '视频与音频'),
          controls: [
            {
              type: 'toggle',
              key: 'smoothVideo',
              title: L('Smooth video (experimental)', '流畅视频（实验性）'),
              desc: L('May add touch latency.', '可能增加触摸延迟。'),
            },
            {
              type: 'toggle',
              key: 'channelMapping',
              title: L('Advanced audio channel mapping', '高级音频通道映射'),
              desc: L(
                'Use usage/content-type routing instead of stream type.',
                '使用 usage/content-type 而不是 stream type 路由。',
              ),
            },
          ],
        },
        {
          id: 'experimental-features',
          icon: 'flask',
          title: L('Experimental features', '实验性功能'),
          controls: [
            {
              type: 'action',
              id: 'experimentalDisplay',
              title: L('Experimental display', '实验性显示'),
              desc: L('More options for the projection surface.', '更多投屏画面相关选项。'),
            },
          ],
        },
      ],
    },
    {
      id: 'about',
      scope: 'shared',
      icon: 'about',
      title: L('About', '关于'),
      summary: L('Version, updates and licences', '版本、更新与许可'),
      sections: [
        {
          id: 'about',
          icon: 'about',
          title: L('About DiPlay', '关于 DiPlay'),
          controls: [
            { type: 'action', id: 'checkUpdates', title: L('Check for updates', '检查更新') },
            { type: 'action', id: 'repo', title: L('Open source repository', '打开源码仓库') },
            { type: 'action', id: 'licences', title: L('Licences', '开源许可') },
            {
              type: 'note',
              text: L(
                'Not affiliated with Apple or BYD. CarPlay is a trademark of Apple Inc.',
                '与 Apple、比亚迪无隶属关系。CarPlay 是 Apple Inc. 的商标。',
              ),
            },
          ],
        },
      ],
    },
  ];

  // Stamp every control with its storage scope and build a key → scope map.
  const scopeOfKey = {};
  for (const category of categories) {
    const categoryScope = category.scope || 'shared';
    for (const section of category.sections) {
      section.scope = section.scope || categoryScope;
      for (const control of section.controls) {
        if (control.key) {
          control.scope = control.scope || section.scope;
          scopeOfKey[control.key] = control.scope;
        }
      }
    }
  }

  window.DiPlaySettingsData = {
    categories,
    defaults: DEFAULTS,
    scopeOfKey,
    getCategory(id) {
      return categories.find((category) => category.id === id) || categories[0];
    },
  };
})();



