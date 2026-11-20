package dev.brl.glyph;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Small rule store used by notification-driven behaviors.
 *
 * A rule matches when every non-empty condition matches:
 *   app package, sender/title fragment, message/text fragment.
 * If a scope has no enabled rules, that scope accepts its notification type.
 * Media progress and notification progress have separate scopes and never
 * consume the same notification.
 */
public final class Rules {

    public static final String ESSENTIAL = "essential";
    /** Stored under the legacy "transit" key so existing rules survive. */
    public static final String NOTIFICATION = "transit";
    public static final String MEDIA = "media";

    private static final char SEP = '\u001F';

    private Rules() {
    }

    public static final class Rule {
        public String id = "";
        public boolean enabled = true;
        public String pkg = "";
        public String title = "";
        public String text = "";

        public Rule copy() {
            Rule rule = new Rule();
            rule.id = id;
            rule.enabled = enabled;
            rule.pkg = pkg;
            rule.title = title;
            rule.text = text;
            return rule;
        }
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences("glyph_rules", Context.MODE_PRIVATE);
    }

    public static List<Rule> all(Context context, String scope) {
        Set<String> encoded = sp(context).getStringSet(scope, new HashSet<String>());
        List<Rule> rules = new ArrayList<>();
        for (String value : encoded) {
            Rule rule = decode(value);
            if (rule != null) {
                rules.add(rule);
            }
        }
        Collections.sort(rules, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                if (!a.pkg.equals(b.pkg)) {
                    return a.pkg.compareTo(b.pkg);
                }
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return rules;
    }

    public static void save(Context context, String scope, List<Rule> rules) {
        Set<String> encoded = new HashSet<>();
        for (Rule rule : rules) {
            encoded.add(encode(rule));
        }
        sp(context).edit().putStringSet(scope, encoded).apply();
    }

    public static void upsert(Context context, String scope, Rule rule) {
        if (rule.id == null || rule.id.isEmpty()) {
            rule.id = UUID.randomUUID().toString();
        }
        List<Rule> rules = all(context, scope);
        boolean replaced = false;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(rule.id)) {
                rules.set(i, rule);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            rules.add(rule);
        }
        save(context, scope, rules);
    }

    public static void delete(Context context, String scope, String id) {
        List<Rule> rules = all(context, scope);
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (rules.get(i).id.equals(id)) {
                rules.remove(i);
            }
        }
        save(context, scope, rules);
    }

    public static boolean hasEnabled(Context context, String scope) {
        for (Rule rule : all(context, scope)) {
            if (rule.enabled) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches(Context context, String scope, String pkg, String title, String text) {
        boolean any = false;
        String haystackTitle = title == null ? "" : title.toLowerCase();
        String haystackText = text == null ? "" : text.toLowerCase();
        for (Rule rule : all(context, scope)) {
            if (!rule.enabled) {
                continue;
            }
            any = true;
            if (!rule.pkg.isEmpty() && !rule.pkg.equals(pkg)) {
                continue;
            }
            if (!rule.title.isEmpty() && !haystackTitle.contains(rule.title.toLowerCase())) {
                continue;
            }
            if (!rule.text.isEmpty() && !haystackText.contains(rule.text.toLowerCase())) {
                continue;
            }
            return true;
        }
        return !any;
    }

    private static String encode(Rule rule) {
        StringBuilder builder = new StringBuilder();
        builder.append(esc(rule.id)).append(SEP)
                .append(rule.enabled ? '1' : '0').append(SEP)
                .append(esc(rule.pkg)).append(SEP)
                .append(esc(rule.title)).append(SEP)
                .append(esc(rule.text));
        return builder.toString();
    }

    private static Rule decode(String value) {
        String[] parts = value.split(String.valueOf(SEP), -1);
        if (parts.length != 5) {
            return null;
        }
        Rule rule = new Rule();
        rule.id = unesc(parts[0]);
        rule.enabled = "1".equals(parts[1]);
        rule.pkg = unesc(parts[2]);
        rule.title = unesc(parts[3]);
        rule.text = unesc(parts[4]);
        return rule;
    }

    private static String esc(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value.replace(String.valueOf(SEP), " ");
        }
    }

    private static String unesc(String value) {
        if (value.isEmpty()) {
            return "";
        }
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
