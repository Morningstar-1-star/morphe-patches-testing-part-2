package app.morphe.extension.youtube.patches.components;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native YouTube-style inline tag bar for Watch Later & Playlists.
 * Positions directly below the "Manual v" sort row and sticks cleanly on scroll.
 * Reads live rendered playlist items only (zero 401 errors, zero cross-playlist mixing).
 */
final class PlaylistInlineTagsManager {

    private static final String BAR_TAG = "venom_inline_playlist_tag_bar";
    private static final int KEY_ORIG_HEIGHT = 0x5A210001;
    private static final int KEY_SORT_ORIG_BOTTOM_PAD = 0x5A210002;
    private static final int KEY_LIST_WATCHED = 0x5A210003;
    private static final int KEY_ROOT_WATCHED = 0x5A210004;

    private static final long SCAN_DEBOUNCE_MS = 260L;
    private static final int MAX_VIDEOS_PER_PLAYLIST = 350;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean scanQueued = false;

    private static WeakReference<View> watchedRootRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> activePlaylistRvRef = new WeakReference<>(null);
    private static WeakReference<View> activeSortRowRef = new WeakReference<>(null);
    private static WeakReference<HorizontalScrollView> activeBarRef = new WeakReference<>(null);

    private static String currentPlaylistKey = "";
    private static String selectedTag = "All";
    private static String lastRenderedSignature = "";
    private static final Map<String, PlaylistCache> playlistCaches = new HashMap<>();

    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?$");
    private static final Pattern VIEWS_TIME_PATTERN = Pattern.compile(
            "(?i)(\\b\\d[\\d.,]*[kmb]?\\s+views?\\b|\\b\\d+\\s+(sec|second|min|minute|hr|hour|day|week|month|year)s?\\s+ago\\b)"
    );
    private static final Pattern PLAYLIST_STATS_PATTERN = Pattern.compile("(?i)\\b\\d+\\s+videos?\\b");
    private static final Pattern PURE_NUM_PATTERN = Pattern.compile("(?i)^[\\d.,+\\-/#:%\\s]+[kmb]?$");
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}][\\p{L}\\p{Mn}]{3,18}");

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "this", "that", "with", "from", "your", "have", "more", "will", "just", "like",
            "what", "when", "they", "them", "then", "some", "into", "only", "know", "time",
            "very", "much", "also", "even", "were", "been", "here", "there", "where", "which",
            "their", "about", "would", "could", "should", "because", "really", "after", "before",
            "other", "every", "always", "never", "make", "made", "going", "being", "these", "those",
            "video", "videos", "youtube", "channel", "watch", "official", "latest", "full", "part",
            "episode", "explained", "best", "most", "years", "days", "tell", "wont", "dont", "cant",
            "behind", "truth", "story", "real", "shows", "after", "ahead", "stronger", "flipped",
            "upcoming", "golden", "dependence", "largest", "free", "store", "million", "units",
            "develops", "locks", "securing", "baad", "dikha", "asli", "chehra", "fera", "saste",
            "paani", "gulam", "utari", "gali", "nahi", "kuch", "kaise", "kyun", "wala", "liye"
    ));

    private static final class PlaylistCache {
        final Map<String, VideoItem> items = new LinkedHashMap<>();
    }

    private static final class VideoItem {
        final String title;
        final String channel;
        final Set<String> tags;

        VideoItem(String title, String channel, Set<String> tags) {
            this.title = title;
            this.channel = channel;
            this.tags = tags;
        }
    }

    private static final class TagChip {
        final String label;
        final int count;

        TagChip(String label, int count) {
            this.label = label;
            this.count = count;
        }
    }

    private static final class CategoryRule {
        final String name;
        final String[] strongKeywords;
        final String[] supportKeywords;

        CategoryRule(String name, String[] strongKeywords, String[] supportKeywords) {
            this.name = name;
            this.strongKeywords = strongKeywords;
            this.supportKeywords = supportKeywords;
        }
    }

    // Context-weighted rules: Geopolitics / Economy never falsely tagged as Cricket
    private static final CategoryRule[] CATEGORY_RULES = new CategoryRule[]{
            new CategoryRule("Geopolitics",
                    new String[]{"geopolitics", "army base", "military", "defense", "defence", "border",
                            "siliguri", "chicken's neck", "missile", "air force", "navy", "nato", "china",
                            "afghanistan", "afg.", "pak media", "shehbaz", "encounter", "kulgam", "kashmir",
                            "world affairs", "foreign policy", "diplomacy", "war", "conflict", "sanctions",
                            "balochistan", "strategic", "अफ़ग़ानिस्तान", "पाकिस्तान", "सेना"},
                    new String[]{"india", "pakistan", "bangladesh", "russia", "ukraine", "usa", "israel", "iran"}),
            new CategoryRule("Economy",
                    new String[]{"economy", "gdp", "imf", "reforms", "inflation", "stock market", "nifty",
                            "sensex", "billion", "trillion", "finance", "business", "startup", "tariff",
                            "trade", "debt", "rupee", "dollar", "bank", "tax", "budget", "mansoobon", "bijli",
                            "बिजली", "अर्थव्यवस्था"},
                    new String[]{"growth", "money", "market", "company", "export", "import"}),
            new CategoryRule("Tech & Science",
                    new String[]{"lithium", "battery", "artificial intelligence", "llm", "smartphone",
                            "android", "iphone", "processor", "chip", "gpu", "coding", "programming",
                            "github", "software", "open source", "physics", "space", "isro", "nasa",
                            "quantum", "robot", "electric vehicle", "science", "energy", "बैटरी"},
                    new String[]{"tech", "app", "update", "review", "future", "develops", "device"}),
            new CategoryRule("Movies",
                    new String[]{"cinema", "hollywood", "bollywood", "tollywood", "movie", "film", "trailer",
                            "teaser", "box office", "ending explained", "web series", "netflix", "marvel",
                            "shaktimaan", "superhero", "director", "actor", "soundtrack", "फिल्म"},
                    new String[]{"review", "scene", "story", "era", "upcoming", "cast"}),
            new CategoryRule("Gaming",
                    new String[]{"gta", "gta 6", "gaming", "gameplay", "walkthrough", "fortnite",
                            "clash of clans", "boom beach", "warhammer", "minecraft", "valorant", "bgmi",
                            "pubg", "ps5", "xbox", "steam", "esports", "carryminati", "kaashvi", "boss fight"},
                    new String[]{"game", "play", "player", "rank", "level", "map"}),
            new CategoryRule("Cricket",
                    new String[]{"cricket", "ipl", "odi", "t20", "test match", "world cup", "bcci", "icc",
                            "wicket", "century", "batting", "bowling", "kohli", "rohit", "bumrah",
                            "dhoni", "gill", "babar", "stadium", "scorecard", "innings", "क्रिकेट"},
                    new String[]{"match", "highlights", "vs", "series", "captain"}),
            new CategoryRule("Music",
                    new String[]{"official video", "lyrics", "lyric video", "song", "album", "remix", "lofi",
                            "orchestral", "theme music", "nordic", "folk", "rap", "hip hop", "acoustic",
                            "concert", "full song", "jukebox", "bgm"},
                    new String[]{"music", "track", "mix", "audio", "singer"}),
            new CategoryRule("Anime",
                    new String[]{"anime", "manga", "iruma", "chainsaw man", "one piece", "naruto", "bleach",
                            "jujutsu", "demon slayer", "manhwa", "chapter", "arc", "amv"},
                    new String[]{"season", "sub", "dub", "character"}),
            new CategoryRule("Cars & Auto",
                    new String[]{"suv", "cars", "bike", "motorcycle", "engine", "mahindra", "tata",
                            "safari", "storme", "scorpio", "thar", "toyota", "bmw", "mercedes", "mileage"},
                    new String[]{"drive", "speed", "offroad", "review"}),
            new CategoryRule("News",
                    new String[]{"breaking news", "latest news", "amar ujala", "aaj tak", "ndtv", "bbc",
                            "live news", "headline", "politics", "election", "parliament", "न्यूज़", "खबर"},
                    new String[]{"news", "today", "update", "report"})
    };

    private PlaylistInlineTagsManager() {
    }

    /**
     * Called from CommentsSearchFilter.onSectionListCreated whenever YouTube creates a list view.
     */
    static void attach(View listView) {
        if (listView == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                if (listView.isAttachedToWindow()) {
                    hookWindowRoot(listView.getRootView());
                } else {
                    listView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {
                            v.removeOnAttachStateChangeListener(this);
                            hookWindowRoot(v.getRootView());
                        }

                        @Override
                        public void onViewDetachedFromWindow(View v) {
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void hookWindowRoot(View root) {
        if (root == null) {
            return;
        }
        if (Boolean.TRUE.equals(root.getTag(KEY_ROOT_WATCHED))) {
            scheduleScan();
            return;
        }
        root.setTag(KEY_ROOT_WATCHED, Boolean.TRUE);
        watchedRootRef = new WeakReference<>(root);

        ViewTreeObserver vto = root.getViewTreeObserver();
        if (vto.isAlive()) {
            vto.addOnGlobalLayoutListener(PlaylistInlineTagsManager::scheduleScan);
            vto.addOnPreDrawListener(() -> {
                updateBarStickyPosition();
                return true;
            });
        }
        scheduleScan();
    }

    private static void scheduleScan() {
        if (scanQueued) {
            return;
        }
        scanQueued = true;
        MAIN.postDelayed(() -> {
            scanQueued = false;
            scanPlaylistScreen();
        }, SCAN_DEBOUNCE_MS);
    }

    private static void scanPlaylistScreen() {
        View root = watchedRootRef.get();
        if (root == null || !root.isAttachedToWindow()) {
            return;
        }

        try {
            ViewGroup playlistRv = findActivePlaylistRecyclerView(root);
            if (playlistRv == null) {
                hideActiveBar();
                return;
            }

            watchRecyclerChildren(playlistRv);

            PlaylistCache cache = playlistCaches.get(currentPlaylistKey);
            if (cache == null) {
                cache = new PlaylistCache();
                playlistCaches.put(currentPlaylistKey, cache);
            }

            int barHeight = dp(playlistRv.getContext(), 48);
            View foundSortRow = null;
            int childCount = playlistRv.getChildCount();

            for (int i = 0; i < childCount; i++) {
                View row = playlistRv.getChildAt(i);
                if (row == null) continue;

                if (isSortControlRow(row)) {
                    foundSortRow = row;
                    restoreRowHeight(row);
                    continue;
                }

                VideoItem item = parsePlaylistVideoRow(row);
                if (item == null) {
                    restoreRowHeight(row);
                    continue;
                }

                if (cache.items.size() < MAX_VIDEOS_PER_PLAYLIST || cache.items.containsKey(item.title)) {
                    cache.items.put(item.title, item);
                }

                boolean visible = "All".equals(selectedTag) || matchesSelectedTag(item, selectedTag);
                setRowFiltered(row, !visible);
            }

            TagChip[] chips = buildTagChips(cache);
            boolean showBar = chips.length > 1;

            // Reserve exact 48dp space right underneath "Manual v" so chip bar sits cleanly above Video #1
            View prevSortRow = activeSortRowRef.get();
            if (prevSortRow != null && prevSortRow != foundSortRow) {
                restoreSortRowPadding(prevSortRow);
            }
            activeSortRowRef = new WeakReference<>(foundSortRow);
            if (foundSortRow != null) {
                if (showBar) {
                    applySortRowBottomGap(foundSortRow, barHeight);
                } else {
                    restoreSortRowPadding(foundSortRow);
                }
            }

            renderChipBar(playlistRv, chips, barHeight);
            updateBarStickyPosition();
        } catch (Throwable ignored) {
        }
    }

    private static ViewGroup findActivePlaylistRecyclerView(View root) {
        List<ViewGroup> rvs = new ArrayList<>();
        findVisibleRecyclerViews(root, rvs);

        // First: check for a visible playlist header ("Play all" + "X videos" / "Manual" / "Watch later")
        for (ViewGroup rv : rvs) {
            if (isInsideCommentsPanel(rv)) continue;
            String detectedTitle = detectPlaylistTitleFromHeader(rv);
            if (detectedTitle != null) {
                if (!detectedTitle.equals(currentPlaylistKey)) {
                    currentPlaylistKey = detectedTitle;
                    selectedTag = "All";
                    lastRenderedSignature = "";
                }
                activePlaylistRvRef = new WeakReference<>(rv);
                return rv;
            }
        }

        // Second: if user scrolled down past the header, keep the already-identified playlist active
        ViewGroup existing = activePlaylistRvRef.get();
        if (existing != null && existing.isAttachedToWindow() && existing.isShown()
                && !isInsideCommentsPanel(existing) && hasPlaylistVideoRows(existing)) {
            return existing;
        }

        return null;
    }

    private static String detectPlaylistTitleFromHeader(ViewGroup rv) {
        if (rv.getHeight() < dp(rv.getContext(), 200)) {
            return null;
        }
        boolean hasPlayAll = false;
        boolean hasStatsOrSort = false;
        String watchLaterTitle = null;

        int limit = Math.min(rv.getChildCount(), 8);
        for (int i = 0; i < limit; i++) {
            View child = rv.getChildAt(i);
            if (child == null) continue;
            List<String> texts = extractVisibleTexts(child);
            for (String raw : texts) {
                String lower = raw.toLowerCase(Locale.ROOT).trim();
                if (lower.equals("play all") || lower.equals("shuffle")) {
                    hasPlayAll = true;
                } else if (PLAYLIST_STATS_PATTERN.matcher(lower).find()
                        || lower.contains("playlist •") || lower.contains("playlist ·")
                        || lower.equals("manual") || lower.startsWith("date added")) {
                    hasStatsOrSort = true;
                } else if (lower.equals("watch later")) {
                    watchLaterTitle = "Watch later";
                }
            }
        }

        if (!hasPlayAll || !hasStatsOrSort) {
            return null;
        }
        if (watchLaterTitle != null) {
            return watchLaterTitle;
        }
        return extractPlaylistName(rv);
    }

    private static String extractPlaylistName(ViewGroup rv) {
        int limit = Math.min(rv.getChildCount(), 4);
        for (int i = 0; i < limit; i++) {
            View child = rv.getChildAt(i);
            if (child == null) continue;
            List<String> texts = extractVisibleTexts(child);
            for (String raw : texts) {
                String t = raw.trim();
                String lower = t.toLowerCase(Locale.ROOT);
                if (t.length() < 2 || t.length() > 70) continue;
                if (lower.equals("play all") || lower.equals("shuffle") || lower.equals("manual")
                        || lower.startsWith("by ") || lower.contains("videos")
                        || lower.contains("views") || lower.contains("private")
                        || lower.contains("public") || lower.contains("unlisted")
                        || DURATION_PATTERN.matcher(t).matches()) {
                    continue;
                }
                return t;
            }
        }
        return "Playlist";
    }

    private static boolean hasPlaylistVideoRows(ViewGroup rv) {
        int limit = Math.min(rv.getChildCount(), 6);
        for (int i = 0; i < limit; i++) {
            if (parsePlaylistVideoRow(rv.getChildAt(i)) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSortControlRow(View row) {
        if (row == null) return false;
        List<String> texts = extractVisibleTexts(row);
        if (texts.isEmpty() || texts.size() > 4) return false;
        for (String raw : texts) {
            String lower = raw.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("manual") || lower.startsWith("date added")
                    || lower.equals("most popular") || lower.startsWith("date published")
                    || lower.equals("sort")) {
                return true;
            }
        }
        return false;
    }

    private static VideoItem parsePlaylistVideoRow(View row) {
        if (row == null) return null;
        List<String> texts = extractVisibleTexts(row);
        if (texts.size() < 2) return null;

        boolean hasDurationOrViews = false;
        String title = "";
        String channel = "";

        for (String raw : texts) {
            String t = raw.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.equals("play all") || lower.equals("manual")
                    || PLAYLIST_STATS_PATTERN.matcher(lower).find()) {
                return null;
            }
            if (DURATION_PATTERN.matcher(t).matches() || VIEWS_TIME_PATTERN.matcher(t).find()) {
                hasDurationOrViews = true;
                continue;
            }
            if (t.length() >= 8 && t.length() > title.length()) {
                if (!title.isEmpty() && channel.isEmpty()) {
                    channel = title;
                }
                title = t;
            } else if (t.length() >= 2 && t.length() <= 48 && !PURE_NUM_PATTERN.matcher(t).matches()) {
                if (channel.isEmpty() && !t.equals(title)) {
                    channel = t;
                }
            }
        }

        if (!hasDurationOrViews || title.length() < 5) {
            return null;
        }
        return new VideoItem(title, channel, classifyTags(title, channel));
    }

    private static Set<String> classifyTags(String title, String channel) {
        Set<String> tags = new LinkedHashSet<>();
        String combined = (title + " " + channel).toLowerCase(Locale.ROOT);

        String primaryCat = null;
        int bestScore = 0;
        Map<String, Integer> scores = new LinkedHashMap<>();

        for (CategoryRule rule : CATEGORY_RULES) {
            int score = 0;
            for (String kw : rule.strongKeywords) {
                if (combined.contains(kw)) {
                    score += 3;
                }
            }
            if (score > 0) {
                for (String sup : rule.supportKeywords) {
                    if (combined.contains(sup)) {
                        score += 1;
                    }
                }
            }
            if (score >= 3) {
                scores.put(rule.name, score);
                if (score > bestScore) {
                    bestScore = score;
                    primaryCat = rule.name;
                }
            }
        }

        if (primaryCat != null) {
            tags.add(primaryCat);
            for (String cat : scores.keySet()) {
                Integer sc = scores.get(cat);
                if (!cat.equals(primaryCat) && sc != null && sc >= 3 && tags.size() < 2) {
                    tags.add(cat);
                }
            }
        }

        Matcher m = WORD_PATTERN.matcher(title.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (!STOP_WORDS.contains(w)) {
                tags.add("kw:" + w);
            }
        }

        return tags;
    }

    private static boolean matchesSelectedTag(VideoItem item, String tag) {
        if (item.tags.contains(tag)) {
            return true;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        if (item.tags.contains("kw:" + lower)) {
            return true;
        }
        return item.title.toLowerCase(Locale.ROOT).contains(lower)
                || item.channel.toLowerCase(Locale.ROOT).contains(lower);
    }

    private static TagChip[] buildTagChips(PlaylistCache cache) {
        Map<String, Integer> catCounts = new HashMap<>();
        Map<String, Integer> kwCounts = new HashMap<>();

        for (VideoItem item : cache.items.values()) {
            for (String tag : item.tags) {
                if (tag.startsWith("kw:")) {
                    String w = tag.substring(3);
                    Integer prev = kwCounts.get(w);
                    kwCounts.put(w, prev == null ? 1 : prev + 1);
                } else {
                    Integer prev = catCounts.get(tag);
                    catCounts.put(tag, prev == null ? 1 : prev + 1);
                }
            }
        }

        List<TagChip> result = new ArrayList<>();
        result.add(new TagChip("All", cache.items.size()));

        List<TagChip> cats = new ArrayList<>();
        for (String cat : catCounts.keySet()) {
            Integer c = catCounts.get(cat);
            if (c != null && c >= 1) {
                cats.add(new TagChip(cat, c));
            }
        }
        cats.sort((a, b) -> Integer.compare(b.count, a.count));
        result.addAll(cats);

        List<TagChip> kws = new ArrayList<>();
        for (String w : kwCounts.keySet()) {
            Integer c = kwCounts.get(w);
            if (c != null && c >= 2) {
                String label = capitalize(w);
                boolean dup = false;
                for (TagChip existing : cats) {
                    if (existing.label.equalsIgnoreCase(label)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    kws.add(new TagChip(label, c));
                }
            }
        }
        kws.sort((a, b) -> Integer.compare(b.count, a.count));
        int kwLimit = Math.min(7, kws.size());
        for (int i = 0; i < kwLimit; i++) {
            result.add(kws.get(i));
        }

        return result.toArray(new TagChip[0]);
    }

    private static void renderChipBar(ViewGroup playlistRv, TagChip[] chips, int barHeight) {
        ViewGroup host = findOverlayHost(playlistRv);
        if (host == null) {
            return;
        }
        Context ctx = host.getContext();
        HorizontalScrollView bar = host.findViewWithTag(BAR_TAG);

        if (chips.length <= 1) {
            if (bar != null) {
                bar.setVisibility(View.GONE);
            }
            return;
        }

        if (bar == null) {
            bar = new HorizontalScrollView(ctx);
            bar.setTag(BAR_TAG);
            bar.setHorizontalScrollBarEnabled(false);
            bar.setElevation(dp(ctx, 4));
            host.addView(bar, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barHeight));
        }

        activeBarRef = new WeakReference<>(bar);
        bar.setVisibility(View.VISIBLE);
        bar.bringToFront();

        // Only rebuild chip views when tag counts or selection actually changed
        StringBuilder sig = new StringBuilder(currentPlaylistKey).append('|').append(selectedTag);
        for (TagChip chip : chips) {
            sig.append('|').append(chip.label).append(':').append(chip.count);
        }
        String newSig = sig.toString();
        if (newSig.equals(lastRenderedSignature) && bar.getChildCount() > 0) {
            return;
        }
        lastRenderedSignature = newSig;

        boolean dark = isDarkUi(ctx);
        bar.setBackgroundColor(dark ? Color.parseColor("#0F0F0F") : Color.WHITE);
        bar.removeAllViews();

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));

        for (TagChip chip : chips) {
            final String label = chip.label;
            final boolean isSelected = label.equals(selectedTag);
            row.addView(createNativeChip(ctx, label, "All".equals(label) ? 0 : chip.count,
                    isSelected, dark, v -> {
                        if (isSelected && !"All".equals(label)) {
                            selectedTag = "All";
                        } else {
                            selectedTag = label;
                        }
                        lastRenderedSignature = "";
                        scanPlaylistScreen();
                    }));
        }
        bar.addView(row);
    }

    /**
     * Runs at display refresh rate while playlist is open: aligns the chip bar
     * directly beneath "Manual v" and pins it cleanly at the top as you scroll.
     */
    private static void updateBarStickyPosition() {
        HorizontalScrollView bar = activeBarRef.get();
        ViewGroup rv = activePlaylistRvRef.get();
        if (bar == null || bar.getVisibility() != View.VISIBLE || rv == null) {
            return;
        }
        if (!rv.isAttachedToWindow() || !rv.isShown()) {
            bar.setVisibility(View.GONE);
            return;
        }
        ViewParent parentObj = bar.getParent();
        if (!(parentObj instanceof View host)) {
            return;
        }

        int[] hostLoc = new int[2];
        int[] rvLoc = new int[2];
        host.getLocationInWindow(hostLoc);
        rv.getLocationInWindow(rvLoc);

        int rvTopInHost = Math.max(0, rvLoc[1] - hostLoc[1]);
        int barHeight = bar.getHeight() > 0 ? bar.getHeight() : dp(bar.getContext(), 48);

        float targetY = rvTopInHost;
        View sortRow = activeSortRowRef.get();
        if (sortRow != null && sortRow.isAttachedToWindow() && sortRow.isShown()) {
            int[] sortLoc = new int[2];
            sortRow.getLocationInWindow(sortLoc);
            int sortBottomInHost = (sortLoc[1] + sortRow.getHeight()) - hostLoc[1];
            targetY = Math.max(rvTopInHost, sortBottomInHost - barHeight);
        }

        int maxHostY = Math.max(rvTopInHost, host.getHeight() - barHeight);
        if (targetY > maxHostY) {
            targetY = maxHostY;
        }
        if (Math.abs(bar.getTranslationY() - targetY) > 0.5f) {
            bar.setTranslationY(targetY);
        }
    }

    private static ViewGroup findOverlayHost(ViewGroup rv) {
        ViewParent p = rv.getParent();
        int hops = 0;
        while (p instanceof ViewGroup vg && hops < 5) {
            String name = vg.getClass().getSimpleName();
            if (!name.contains("SwipeRefresh") && !(vg instanceof LinearLayout)) {
                return vg;
            }
            p = vg.getParent();
            hops++;
        }
        return rv.getParent() instanceof ViewGroup vg ? vg : null;
    }

    private static void hideActiveBar() {
        HorizontalScrollView bar = activeBarRef.get();
        if (bar != null && bar.getVisibility() != View.GONE) {
            bar.setVisibility(View.GONE);
        }
        View sortRow = activeSortRowRef.get();
        if (sortRow != null) {
            restoreSortRowPadding(sortRow);
            activeSortRowRef = new WeakReference<>(null);
        }
    }

    private static void watchRecyclerChildren(ViewGroup rv) {
        if (Boolean.TRUE.equals(rv.getTag(KEY_LIST_WATCHED))) {
            return;
        }
        rv.setTag(KEY_LIST_WATCHED, Boolean.TRUE);
        rv.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                scheduleScan();
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                restoreRowHeight(child);
                restoreSortRowPadding(child);
            }
        });
    }

    private static void applySortRowBottomGap(View sortRow, int extraBottomPx) {
        if (sortRow.getTag(KEY_SORT_ORIG_BOTTOM_PAD) == null) {
            sortRow.setTag(KEY_SORT_ORIG_BOTTOM_PAD, sortRow.getPaddingBottom());
            sortRow.setPadding(
                    sortRow.getPaddingLeft(),
                    sortRow.getPaddingTop(),
                    sortRow.getPaddingRight(),
                    sortRow.getPaddingBottom() + extraBottomPx
            );
        }
    }

    private static void restoreSortRowPadding(View row) {
        if (row == null) return;
        Object saved = row.getTag(KEY_SORT_ORIG_BOTTOM_PAD);
        if (saved instanceof Integer origBottom) {
            row.setPadding(row.getPaddingLeft(), row.getPaddingTop(), row.getPaddingRight(), origBottom);
            row.setTag(KEY_SORT_ORIG_BOTTOM_PAD, null);
        }
    }

    private static void setRowFiltered(View row, boolean hide) {
        if (row == null) return;
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) return;

        if (hide) {
            if (row.getTag(KEY_ORIG_HEIGHT) == null) {
                row.setTag(KEY_ORIG_HEIGHT, lp.height);
            }
            if (lp.height != 0 || row.getVisibility() != View.GONE) {
                lp.height = 0;
                row.setLayoutParams(lp);
                row.setVisibility(View.GONE);
            }
        } else {
            restoreRowHeight(row);
        }
    }

    private static void restoreRowHeight(View row) {
        if (row == null) return;
        Object saved = row.getTag(KEY_ORIG_HEIGHT);
        if (saved instanceof Integer origHeight) {
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp != null && lp.height != origHeight) {
                lp.height = origHeight;
                row.setLayoutParams(lp);
            }
            row.setTag(KEY_ORIG_HEIGHT, null);
        }
        if (row.getVisibility() != View.VISIBLE) {
            row.setVisibility(View.VISIBLE);
        }
    }

    private static View createNativeChip(Context ctx, String text, int count,
                                         boolean isSelected, boolean dark,
                                         View.OnClickListener onClick) {
        LinearLayout chip = new LinearLayout(ctx);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(ctx, 13), dp(ctx, 7), dp(ctx, 13), dp(ctx, 7));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 8));
        if (isSelected) {
            bg.setColor(dark ? Color.WHITE : Color.parseColor("#0F0F0F"));
        } else {
            bg.setColor(dark ? Color.parseColor("#272727") : Color.parseColor("#F2F2F2"));
        }
        chip.setBackground(bg);

        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(isSelected
                ? (dark ? Color.parseColor("#0F0F0F") : Color.WHITE)
                : (dark ? Color.parseColor("#F1F1F1") : Color.parseColor("#0F0F0F")));
        chip.addView(label);

        if (count > 0) {
            TextView countView = new TextView(ctx);
            countView.setText(String.valueOf(count));
            countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            countView.setTextColor(isSelected
                    ? (dark ? Color.parseColor("#444444") : Color.parseColor("#CCCCCC"))
                    : (dark ? Color.parseColor("#AAAAAA") : Color.parseColor("#606060")));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.setMarginStart(dp(ctx, 5));
            countView.setLayoutParams(cLp);
            chip.addView(countView);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(ctx, 8));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(onClick);
        return chip;
    }

    private static List<String> extractVisibleTexts(View root) {
        List<String> out = new ArrayList<>();
        collectVisibleTexts(root, out, 0);
        return out;
    }

    private static void collectVisibleTexts(View view, List<String> out, int depth) {
        if (view == null || depth > 14 || view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView tv) {
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0) {
                out.add(cs.toString());
            }
        } else if (!(view instanceof ImageView) && !(view instanceof ImageButton)) {
            try {
                AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
                if (info != null) {
                    CharSequence t = info.getText();
                    if (t != null && t.length() > 0) {
                        out.add(t.toString());
                    } else {
                        CharSequence desc = info.getContentDescription();
                        if (desc != null && desc.length() > 0) {
                            out.add(desc.toString());
                        }
                    }
                    info.recycle();
                }
            } catch (Throwable ignored) {
            }
        }
        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectVisibleTexts(vg.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static void findVisibleRecyclerViews(View view, List<ViewGroup> out) {
        if (view == null || !view.isShown()) return;
        if (view instanceof ViewGroup vg) {
            if (isRecyclerView(vg)) {
                out.add(vg);
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                findVisibleRecyclerViews(vg.getChildAt(i), out);
            }
        }
    }

    private static boolean isRecyclerView(View view) {
        Class<?> cls = view.getClass();
        while (cls != null && cls != ViewGroup.class && cls != Object.class) {
            if (cls.getSimpleName().contains("RecyclerView")) {
                return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    private static boolean isInsideCommentsPanel(View view) {
        ViewParent p = view.getParent();
        int hops = 0;
        while (p instanceof ViewGroup vg && hops < 6) {
            if (containsCommentsHeader(vg, 0)) {
                return true;
            }
            p = vg.getParent();
            hops++;
        }
        return false;
    }

    private static boolean containsCommentsHeader(View v, int depth) {
        if (v == null || depth > 4 || !v.isShown()) return false;
        if (v instanceof TextView tv) {
            CharSequence cs = tv.getText();
            if (cs != null && "Comments".equalsIgnoreCase(cs.toString().trim())) {
                return true;
            }
        }
        if (v instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (containsCommentsHeader(vg.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isDarkUi(Context ctx) {
        int nightFlags = ctx.getResources().getConfiguration().uiMode & 0x30;
        return nightFlags == 0x20;
    }

    private static int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics());
    }
}
