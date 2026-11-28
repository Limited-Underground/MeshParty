package com.limitedunderground.meshinbox;

import java.util.List;

/**
 * Release notes for the What's new window (D-207), newest first. Add a line for each release;
 * the window shows the releases in the current series (for 0.2.x every 0.2 release).
 */
final class WhatsNew {
    record Release(String version, int text) {}

    static final List<Release> RELEASES = List.of(
            new Release("0.2.3", R.string.whats_new_0_2_3),
            new Release("0.2.2", R.string.whats_new_0_2_2),
            new Release("0.2.1", R.string.whats_new_0_2_1),
            new Release("0.2.0", R.string.whats_new_0_2_0));

    private WhatsNew() {}
}
