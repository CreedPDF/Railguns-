package com.creedpdf.orbitalrailgun.krl;

import java.util.function.Consumer;

/** Hooks the client fills in (block entity sounds and effects); no-ops on a dedicated server. */
public final class KrlClientHooks {
    private KrlClientHooks() {}

    public static Consumer<KrlLauncherBlockEntity> launcher = be -> {};

    static void launcherTick(KrlLauncherBlockEntity be) {
        launcher.accept(be);
    }
}
