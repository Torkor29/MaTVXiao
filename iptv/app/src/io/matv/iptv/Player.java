package io.matv.iptv;

import java.util.List;

/** What the player plays: the list the stream was picked from (to zap through it) and where in it. */
final class Player {
    private Player() {}
    static List<Xtream.Item> queue;
    static int index;
    static Xtream x;
}
