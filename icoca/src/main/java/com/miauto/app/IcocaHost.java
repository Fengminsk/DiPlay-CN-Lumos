package com.miauto.app;

/**
 * Host-side hooks for the ICCOA engine.
 *
 * MiAuto reached into its own {@code IcocaProjectionActivity} directly; in DiPlay the projection
 * screen belongs to the CarPlay-style host UI, so the engine asks through this seam instead and
 * the host registers an implementation at startup.
 */
public final class IcocaHost {

    /** Implemented by the DiPlay projection screen. */
    public interface ProjectionScreen {
        /** Bring the projection page to the front, creating it if needed. */
        void open();

        /** Whether the projection page is currently resumed and visible. */
        boolean isForeground();
    }

    private static volatile ProjectionScreen screen;

    private IcocaHost() {
    }

    public static void setProjectionScreen(ProjectionScreen value) {
        screen = value;
    }

    public static boolean isForeground() {
        ProjectionScreen current = screen;
        return current != null && current.isForeground();
    }

    public static void openProjection() {
        ProjectionScreen current = screen;
        if (current != null) {
            current.open();
        }
    }
}
