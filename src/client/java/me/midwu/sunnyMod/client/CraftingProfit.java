package me.midwu.sunnyMod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crafting-aware sell path finder for F7 container worth.
 *
 * <p>Recipe source (1.21.11): vanilla datapack JSON under {@code data/&#42;/recipe/},
 * because client {@code RecipeManager} no longer exposes {@code getAllOfType}.
 * Compaction pairs are always merged in.
 *
 * <p><b>Custom / OP items never enter the craft bag.</b> Only stacks with no
 * {@code CUSTOM_NAME} and display name equal to vanilla {@code Item.getName()}.
 */
public final class CraftingProfit {

    private CraftingProfit() {}

    /** One ingredient line: any of {@code anyOf} names, needing {@code count} total. */
    public record Need(List<String> anyOf, int count) {
        static Need exact(String item, int count) {
            return new Need(List.of(item), count);
        }
    }

    public record Recipe(String id, String output, int outputCount, List<Need> inputs) {}

    public record Compaction(String unitVanilla, String blockVanilla, int ratio) {}

    public record Plan(
            Map<String, Integer> finalCounts,
            double sellValue,
            List<String> steps,
            int recipesConsidered,
            double baseSellValue
    ) {
        public boolean improved() {
            return !steps.isEmpty() && sellValue > baseSellValue + 0.001;
        }
    }

    public static final List<Compaction> COMPACTIONS = List.of(
            new Compaction("Iron Ingot", "Block of Iron", 9),
            new Compaction("Gold Ingot", "Block of Gold", 9),
            new Compaction("Diamond", "Block of Diamond", 9),
            new Compaction("Emerald", "Block of Emerald", 9),
            new Compaction("Lapis Lazuli", "Lapis Lazuli Block", 9),
            new Compaction("Redstone Dust", "Block of Redstone", 9),
            new Compaction("Coal", "Block of Coal", 9),
            new Compaction("Netherite Ingot", "Block of Netherite", 9),
            new Compaction("Copper Ingot", "Block of Copper", 9),
            new Compaction("Raw Iron", "Block of Raw Iron", 9),
            new Compaction("Raw Gold", "Block of Raw Gold", 9),
            new Compaction("Raw Copper", "Block of Raw Copper", 9),
            new Compaction("Amethyst Shard", "Block of Amethyst", 4),
            new Compaction("Slimeball", "Slime Block", 9),
            new Compaction("Bone Meal", "Bone Block", 9),
            new Compaction("Wheat", "Hay Bale", 9),
            new Compaction("Dried Kelp", "Dried Kelp Block", 9),
            new Compaction("Melon Slice", "Melon", 9),
            new Compaction("Iron Nugget", "Iron Ingot", 9),
            new Compaction("Gold Nugget", "Gold Ingot", 9)
    );

    private static volatile List<Recipe> cachedRecipes = null;

    // ── Plain / OP filters ───────────────────────────────────────────────────

    public static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "").trim();
    }

    /**
     * Ordinary commodity only — safe as a craft ingredient.
     * Rejects anvil/OP renames ({@code CUSTOM_NAME}) and display ≠ vanilla.
     */
    public static boolean isPlainCommodity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.get(DataComponentTypes.CUSTOM_NAME) != null) return false;
        String display = stripFormatting(stack.getName().getString());
        String vanilla = stripFormatting(stack.getItem().getName().getString());
        if (display.isEmpty() || vanilla.isEmpty()) return false;
        return display.equalsIgnoreCase(vanilla);
    }

    // ── Recipe loading ───────────────────────────────────────────────────────

    public static List<Recipe> recipes() {
        List<Recipe> cached = cachedRecipes;
        if (cached != null) return cached;

        List<Recipe> built = new ArrayList<>(compactionRecipes());
        try {
            built.addAll(loadFromDatapackJson());
        } catch (Throwable t) {
            System.err.println("[CraftingProfit] Datapack recipe load failed: " + t.getMessage());
        }

        Map<String, Recipe> byId = new LinkedHashMap<>();
        for (Recipe r : built) {
            byId.putIfAbsent(r.id(), r);
        }
        List<Recipe> list = List.copyOf(byId.values());
        cachedRecipes = list;
        System.out.println("[CraftingProfit] Loaded " + list.size() + " craft recipes");
        return list;
    }

    /** Call if resources reload and recipes must be re-read. */
    public static void invalidateCache() {
        cachedRecipes = null;
    }

    private static List<Recipe> compactionRecipes() {
        List<Recipe> r = new ArrayList<>();
        for (Compaction c : COMPACTIONS) {
            r.add(new Recipe("pack_" + c.blockVanilla(),
                    c.blockVanilla(), 1,
                    List.of(Need.exact(c.unitVanilla(), c.ratio()))));
            r.add(new Recipe("unpack_" + c.blockVanilla(),
                    c.unitVanilla(), c.ratio(),
                    List.of(Need.exact(c.blockVanilla(), 1))));
        }
        return r;
    }

    /**
     * Parse all {@code data/&#42;/recipe/*.json} crafting_shaped / crafting_shapeless
     * from the client resource manager (vanilla jar + datapacks).
     */
    private static List<Recipe> loadFromDatapackJson() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return List.of();
        ResourceManager resources = client.getResourceManager();
        if (resources == null) return List.of();

        Map<Identifier, Resource> found = resources.findResources(
                "recipe",
                id -> id.getPath().endsWith(".json"));

        List<Recipe> out = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> e : found.entrySet()) {
            Identifier id = e.getKey();
            try (InputStreamReader reader = new InputStreamReader(
                    e.getValue().getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                Recipe parsed = parseCraftingJson(id.toString(), root);
                if (parsed != null) out.add(parsed);
            } catch (Throwable ignored) {
                // skip bad / special recipes
            }
        }
        return out;
    }

    private static Recipe parseCraftingJson(String id, JsonObject root) {
        if (!root.has("type")) return null;
        String type = root.get("type").getAsString();
        // strip namespace
        if (type.contains(":")) type = type.substring(type.indexOf(':') + 1);
        if (!type.equals("crafting_shaped") && !type.equals("crafting_shapeless")) {
            return null;
        }

        if (!root.has("result")) return null;
        JsonObject result = root.getAsJsonObject("result");
        String resultId = result.has("id") ? result.get("id").getAsString()
                : (result.has("item") ? result.get("item").getAsString() : null);
        if (resultId == null) return null;
        String outName = itemIdToVanillaName(resultId);
        if (outName == null) return null;
        int outCount = result.has("count") ? result.get("count").getAsInt() : 1;
        if (outCount < 1) outCount = 1;

        Map<String, Need> merged = new LinkedHashMap<>();

        if (type.equals("crafting_shapeless")) {
            if (!root.has("ingredients")) return null;
            for (JsonElement el : root.getAsJsonArray("ingredients")) {
                List<String> names = ingredientNames(el);
                if (names.isEmpty()) return null; // tag too large / unresolvable
                addNeed(merged, names, 1);
            }
        } else {
            // shaped: key + pattern
            if (!root.has("key") || !root.has("pattern")) return null;
            JsonObject key = root.getAsJsonObject("key");
            Map<Character, List<String>> keyMap = new HashMap<>();
            for (Map.Entry<String, JsonElement> ke : key.entrySet()) {
                if (ke.getKey().isEmpty()) continue;
                char ch = ke.getKey().charAt(0);
                List<String> names = ingredientNames(ke.getValue());
                if (names.isEmpty()) return null;
                keyMap.put(ch, names);
            }
            JsonArray pattern = root.getAsJsonArray("pattern");
            for (JsonElement rowEl : pattern) {
                String row = rowEl.getAsString();
                for (int i = 0; i < row.length(); i++) {
                    char ch = row.charAt(i);
                    if (ch == ' ' || ch == '\t') continue;
                    List<String> names = keyMap.get(ch);
                    if (names == null || names.isEmpty()) return null;
                    addNeed(merged, names, 1);
                }
            }
        }

        if (merged.isEmpty()) return null;
        return new Recipe(id, outName, outCount, List.copyOf(merged.values()));
    }

    private static void addNeed(Map<String, Need> merged, List<String> names, int add) {
        String k = String.join("|", names);
        Need prev = merged.get(k);
        if (prev == null) merged.put(k, new Need(names, add));
        else merged.put(k, new Need(names, prev.count() + add));
    }

    /**
     * Resolve a recipe ingredient JSON element to vanilla display names.
     * Supports {@code item}, {@code id}, {@code tag}, and arrays of those.
     * Returns empty if the tag expands to more than 64 items (unusable).
     */
    private static List<String> ingredientNames(JsonElement el) {
        if (el == null || el.isJsonNull()) return List.of();
        if (el.isJsonArray()) {
            List<String> all = new ArrayList<>();
            for (JsonElement sub : el.getAsJsonArray()) {
                all.addAll(ingredientNames(sub));
            }
            // unique
            List<String> uniq = new ArrayList<>();
            for (String n : all) {
                if (!uniq.contains(n)) uniq.add(n);
            }
            return uniq.size() > 64 ? List.of() : uniq;
        }
        if (!el.isJsonObject()) {
            // bare string item id (older format)
            if (el.isJsonPrimitive()) {
                String name = itemIdToVanillaName(el.getAsString());
                return name != null ? List.of(name) : List.of();
            }
            return List.of();
        }
        JsonObject o = el.getAsJsonObject();
        if (o.has("item")) {
            String name = itemIdToVanillaName(o.get("item").getAsString());
            return name != null ? List.of(name) : List.of();
        }
        if (o.has("id") && !o.has("tag")) {
            String name = itemIdToVanillaName(o.get("id").getAsString());
            return name != null ? List.of(name) : List.of();
        }
        if (o.has("tag")) {
            return expandItemTag(o.get("tag").getAsString());
        }
        return List.of();
    }

    private static List<String> expandItemTag(String tagId) {
        try {
            Identifier id = Identifier.of(tagId.contains(":") ? tagId : "minecraft:" + tagId);
            var tagKey = net.minecraft.registry.tag.TagKey.of(Registries.ITEM.getKey(), id);
            List<String> names = new ArrayList<>();
            for (var entry : Registries.ITEM.iterateEntries(tagKey)) {
                Item item = entry.value();
                String n = stripFormatting(item.getName().getString());
                if (!n.isEmpty() && !names.contains(n)) names.add(n);
            }
            return names.size() > 64 ? List.of() : names;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static String itemIdToVanillaName(String itemId) {
        try {
            Identifier id = Identifier.of(itemId.contains(":") ? itemId : "minecraft:" + itemId);
            Optional<Item> item = Registries.ITEM.getOptionalValue(id);
            if (item.isEmpty()) return null;
            String n = stripFormatting(item.get().getName().getString());
            return n.isEmpty() || n.equalsIgnoreCase("Air") ? null : n;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Pricing / apply ──────────────────────────────────────────────────────

    static double bestUnitPrice(Map<String, List<Container_reader.BestBuyOffer>> offers, String name) {
        if (name == null) return -1;
        List<Container_reader.BestBuyOffer> list = offers.get(name);
        if (list != null && !list.isEmpty()) return list.getFirst().price;
        for (var e : offers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().getFirst().price;
            }
        }
        return -1;
    }

    static double bagValue(Map<String, Integer> bag,
                           Map<String, List<Container_reader.BestBuyOffer>> offers) {
        double v = 0;
        for (var e : bag.entrySet()) {
            if (e.getValue() <= 0) continue;
            double p = bestUnitPrice(offers, e.getKey());
            if (p > 0) v += p * e.getValue();
        }
        return v;
    }

    static int haveForNeed(Need need, Map<String, Integer> bag) {
        int sum = 0;
        for (String n : need.anyOf()) sum += bag.getOrDefault(n, 0);
        return sum;
    }

    static int maxCrafts(Recipe recipe, Map<String, Integer> bag) {
        int max = Integer.MAX_VALUE;
        for (Need n : recipe.inputs()) {
            int have = haveForNeed(n, bag);
            if (have < n.count()) return 0;
            max = Math.min(max, have / n.count());
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    static Map<String, Integer> applyCraft(Recipe recipe, Map<String, Integer> bag, int times) {
        if (times <= 0) return bag;
        Map<String, Integer> next = new HashMap<>(bag);
        for (Need n : recipe.inputs()) {
            int left = n.count() * times;
            for (String name : n.anyOf()) {
                if (left <= 0) break;
                int have = next.getOrDefault(name, 0);
                if (have <= 0) continue;
                int take = Math.min(have, left);
                next.merge(name, -take, Integer::sum);
                left -= take;
            }
        }
        next.merge(recipe.output(), recipe.outputCount() * times, Integer::sum);
        next.entrySet().removeIf(e -> e.getValue() <= 0);
        return next;
    }

    static Set<String> usefulOutputs(
            Map<String, List<Container_reader.BestBuyOffer>> offers,
            List<Recipe> all,
            int maxDepth) {
        Set<String> useful = ConcurrentHashMap.newKeySet();
        for (Recipe r : all) {
            if (bestUnitPrice(offers, r.output()) > 0) useful.add(r.output());
        }
        for (int d = 0; d < maxDepth; d++) {
            Set<String> needed = ConcurrentHashMap.newKeySet();
            for (Recipe r : all) {
                if (!useful.contains(r.output())) continue;
                for (Need n : r.inputs()) needed.addAll(n.anyOf());
            }
            boolean changed = false;
            for (Recipe r : all) {
                if (needed.contains(r.output()) && useful.add(r.output())) changed = true;
            }
            if (!changed) break;
        }
        return useful;
    }

    public static Plan optimize(
            Map<String, Integer> start,
            Map<String, List<Container_reader.BestBuyOffer>> offers,
            int maxDepth) {

        Map<String, Integer> clean = new HashMap<>();
        for (var e : start.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) clean.put(e.getKey(), e.getValue());
        }

        List<Recipe> all = recipes();
        Set<String> useful = usefulOutputs(offers, all, maxDepth);
        List<Recipe> candidates = new ArrayList<>();
        for (Recipe r : all) {
            if (useful.contains(r.output())) candidates.add(r);
        }

        double base = bagValue(clean, offers);
        SearchBest best = new SearchBest(clean, base, List.of());
        search(clean, offers, maxDepth, List.of(), best, candidates);
        return new Plan(best.bag, best.value, best.steps, candidates.size(), base);
    }

    private static final class SearchBest {
        Map<String, Integer> bag;
        double value;
        List<String> steps;

        SearchBest(Map<String, Integer> bag, double value, List<String> steps) {
            this.bag = bag;
            this.value = value;
            this.steps = steps;
        }
    }

    private static void search(
            Map<String, Integer> bag,
            Map<String, List<Container_reader.BestBuyOffer>> offers,
            int depthLeft,
            List<String> stepsSoFar,
            SearchBest best,
            List<Recipe> candidates) {

        double here = bagValue(bag, offers);
        if (here > best.value + 0.001) {
            best.value = here;
            best.bag = new HashMap<>(bag);
            best.steps = List.copyOf(stepsSoFar);
        }
        if (depthLeft <= 0) return;

        for (Recipe recipe : candidates) {
            int max = maxCrafts(recipe, bag);
            if (max <= 0) continue;
            Map<String, Integer> next = applyCraft(recipe, bag, max);
            if (next.equals(bag)) continue;
            String step = String.format(Locale.US, "%dx %s → %s",
                    max, shortId(recipe.id()), recipe.output());
            if (stepsSoFar.contains(step)) continue;
            List<String> nextSteps = new ArrayList<>(stepsSoFar);
            nextSteps.add(step);
            search(next, offers, depthLeft - 1, nextSteps, best, candidates);
        }
    }

    private static String shortId(String id) {
        int slash = id.lastIndexOf('/');
        int colon = id.lastIndexOf(':');
        int cut = Math.max(slash, colon);
        String s = cut >= 0 && cut + 1 < id.length() ? id.substring(cut + 1) : id;
        if (s.endsWith(".json")) s = s.substring(0, s.length() - 5);
        return s;
    }
}