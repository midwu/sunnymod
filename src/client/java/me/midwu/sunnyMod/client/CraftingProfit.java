package me.midwu.sunnyMod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.server.MinecraftServer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
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
        // Always ensure iron chain (common profit case) even if loaders fail
        built.add(new Recipe("minecraft:iron_chain", "Iron Chain", 16,
                List.of(
                        Need.exact("Iron Nugget", 2),
                        Need.exact("Iron Ingot", 1)
                )));

        int fromServer = 0, fromJar = 0;
        try {
            List<Recipe> server = loadFromIntegratedServer();
            fromServer = server.size();
            built.addAll(server);
        } catch (Throwable t) {
            System.err.println("[CraftingProfit] Server recipe load failed: " + t.getMessage());
        }
        try {
            List<Recipe> jar = loadFromMinecraftJar();
            fromJar = jar.size();
            built.addAll(jar);
        } catch (Throwable t) {
            System.err.println("[CraftingProfit] Jar recipe load failed: " + t.getMessage());
        }

        Map<String, Recipe> byId = new LinkedHashMap<>();
        for (Recipe r : built) {
            byId.putIfAbsent(r.id(), r);
        }
        List<Recipe> list = List.copyOf(byId.values());
        cachedRecipes = list;
        System.out.println("[CraftingProfit] Loaded " + list.size()
                + " craft recipes (server=" + fromServer + ", jar=" + fromJar + ")");
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
     * Singleplayer / LAN: integrated server has {@link ServerRecipeManager#values()}.
     * Multiplayer clients have no server — jar loader is used instead.
     */
    private static List<Recipe> loadFromIntegratedServer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return List.of();
        MinecraftServer server = client.getServer();
        if (server == null) return List.of();

        var rm = server.getRecipeManager();
        if (!(rm instanceof ServerRecipeManager srm)) return List.of();

        List<ItemStack> empty9 = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) empty9.add(ItemStack.EMPTY);
        CraftingRecipeInput emptyInput = CraftingRecipeInput.create(3, 3, empty9);
        var lookup = server.getRegistryManager();

        List<Recipe> out = new ArrayList<>();
        for (RecipeEntry<?> entry : srm.values()) {
            try {
                net.minecraft.recipe.Recipe<?> raw = entry.value();
                if (raw.getType() != RecipeType.CRAFTING) continue;
                if (!(raw instanceof CraftingRecipe crafting)) continue;

                ItemStack result = crafting.craft(emptyInput, lookup);
                if (result == null || result.isEmpty()) continue;
                String outName = stripFormatting(result.getItem().getName().getString());
                if (outName.isEmpty()) continue;
                int outCount = Math.max(1, result.getCount());

                var placement = crafting.getIngredientPlacement();
                if (placement == null) continue;
                List<Ingredient> ingredients = placement.getIngredients();
                if (ingredients == null || ingredients.isEmpty()) continue;

                Map<String, Need> merged = new LinkedHashMap<>();
                for (Ingredient ing : ingredients) {
                    if (ing == null || ing.isEmpty()) continue;
                    List<String> names = expandIngredient(ing);
                    if (names.isEmpty()) continue;
                    addNeed(merged, names, 1);
                }
                if (merged.isEmpty()) continue;

                String id = entry.id().getValue().toString();
                out.add(new Recipe(id, outName, outCount, List.copyOf(merged.values())));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<String> expandIngredient(Ingredient ing) {
        List<String> names = new ArrayList<>();
        try {
            ing.getMatchingItems().forEach(entry -> {
                String n = stripFormatting(entry.value().getName().getString());
                if (!n.isEmpty() && !names.contains(n)) names.add(n);
            });
        } catch (Throwable t) {
            // fallback: test all items is too slow here — skip
        }
        return names.size() > 64 ? List.of() : names;
    }

    /**
     * Read {@code data/minecraft/recipe/*.json} from the minecraft game jar
     * (works on multiplayer clients where there is no integrated server).
     */
    private static List<Recipe> loadFromMinecraftJar() {
        List<Recipe> out = new ArrayList<>();
        var opt = FabricLoader.getInstance().getModContainer("minecraft");
        if (opt.isEmpty()) return out;

        for (Path root : opt.get().getRootPaths()) {
            Path recipeDir = root.resolve("data/minecraft/recipe");
            if (!Files.isDirectory(recipeDir)) continue;
            try (Stream<Path> walk = Files.walk(recipeDir)) {
                walk.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    try (InputStreamReader reader = new InputStreamReader(
                            Files.newInputStream(p), StandardCharsets.UTF_8)) {
                        JsonObject rootJson = JsonParser.parseReader(reader).getAsJsonObject();
                        String id = "minecraft:" + recipeDir.relativize(p).toString()
                                .replace('\\', '/').replace(".json", "");
                        Recipe parsed = parseCraftingJson(id, rootJson);
                        if (parsed != null) out.add(parsed);
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable t) {
                System.err.println("[CraftingProfit] walk " + recipeDir + ": " + t.getMessage());
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

    /**
     * Outputs we may craft toward:
     * <ol>
     *   <li>Directly sellable (shop BUYING price &gt; 0)</li>
     *   <li>Intermediates that appear as <em>inputs</em> to a recipe producing (1)</li>
     * </ol>
     * Items with no buy price and that are not intermediates (e.g. Shears) stay out.
     */
    static Set<String> usefulOutputs(
            Map<String, List<Container_reader.BestBuyOffer>> offers,
            List<Recipe> all,
            int maxDepth) {
        // Layer 0: directly sellable outputs
        Set<String> sellable = ConcurrentHashMap.newKeySet();
        for (Recipe r : all) {
            if (bestUnitPrice(offers, r.output()) > 0) sellable.add(r.output());
        }
        // Also bag items that already sell (even if no recipe produces them)
        // (caller adds via offers keys matching bag — handled by bagValue)

        Set<String> useful = ConcurrentHashMap.newKeySet();
        useful.addAll(sellable);

        for (int d = 0; d < maxDepth; d++) {
            // Ingredients required by recipes whose output is already useful
            Set<String> needed = ConcurrentHashMap.newKeySet();
            for (Recipe r : all) {
                if (!useful.contains(r.output())) continue;
                for (Need n : r.inputs()) needed.addAll(n.anyOf());
            }
            // A recipe is useful only if it produces something needed as an ingredient
            // (or already sellable — already in useful)
            boolean changed = false;
            for (Recipe r : all) {
                if (needed.contains(r.output()) && useful.add(r.output())) {
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return useful;
    }

    /** Set true to spam the game log with craft-search decisions. */
    public static volatile boolean DEBUG = true;

    private static void dbg(String msg) {
        if (DEBUG) System.out.println("[CraftingProfit] " + msg);
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

        // Candidates: output must be useful (sellable or intermediate toward sellable)
        List<Recipe> candidates = new ArrayList<>();
        for (Recipe r : all) {
            if (useful.contains(r.output())) candidates.add(r);
        }

        double base = bagValue(clean, offers);

        dbg("—— optimize ——");
        dbg("bag: " + clean);
        dbg("base sell value: " + String.format(Locale.US, "%.2f", base));
        dbg("sellable/useful outputs: " + useful.size() + "  candidates: " + candidates.size());
        if (useful.contains("Shears") || useful.stream().anyMatch(s -> s.equalsIgnoreCase("Shears"))) {
            dbg("WARNING: Shears marked useful (someone buys them, or intermediate?)");
        }
        // Sample bag-relevant candidates (inputs intersect bag)
        int shown = 0;
        for (Recipe r : candidates) {
            boolean touches = false;
            for (Need n : r.inputs()) {
                for (String a : n.anyOf()) {
                    if (clean.containsKey(a) || clean.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(a))) {
                        touches = true;
                        break;
                    }
                }
                if (touches) break;
            }
            if (!touches) continue;
            double outP = bestUnitPrice(offers, r.output());
            dbg(String.format(Locale.US, "  candidate: %s → %s x%d (outPrice=%.2f)",
                    shortId(r.id()), r.output(), r.outputCount(), outP));
            if (++shown >= 25) {
                dbg("  … (more candidates touching bag omitted)");
                break;
            }
        }

        SearchBest best = new SearchBest(clean, base, List.of());
        search(clean, offers, maxDepth, List.of(), best, candidates, useful);

        // Never report a "better" plan that is not actually worth more
        if (best.value <= base + 0.001) {
            best.bag = clean;
            best.value = base;
            best.steps = List.of();
        }

        dbg(String.format(Locale.US, "result: value=%.2f (base=%.2f) steps=%s bag=%s",
                best.value, base, best.steps, best.bag));
        dbg("—— end optimize ——");

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
            List<Recipe> candidates,
            Set<String> useful) {

        double here = bagValue(bag, offers);
        if (here > best.value + 0.001) {
            best.value = here;
            best.bag = new HashMap<>(bag);
            best.steps = List.copyOf(stepsSoFar);
            if (DEBUG && !stepsSoFar.isEmpty()) {
                dbg(String.format(Locale.US, "  new best %.2f via %s", here, stepsSoFar));
            }
        }
        if (depthLeft <= 0) return;

        for (Recipe recipe : candidates) {
            // Skip dead-end products: output not sellable and not an intermediate we still need
            if (!useful.contains(recipe.output())) continue;

            int max = maxCrafts(recipe, bag);
            if (max <= 0) continue;

            // Try full batch only (greedy). Skip if this craft alone would
            // destroy all sellable value with no priced output (quick reject).
            Map<String, Integer> next = applyCraft(recipe, bag, max);
            if (next.equals(bag)) continue;

            double nextVal = bagValue(next, offers);
            double outPrice = bestUnitPrice(offers, recipe.output());
            // If output itself is not sellable, only keep exploring if we still
            // have depth to craft it further into something sellable.
            if (outPrice <= 0 && depthLeft <= 1) {
                // Last step cannot be an unpriced intermediate
                continue;
            }
            // Optional prune: if value already far below best and output unpriced, skip
            if (outPrice <= 0 && nextVal < best.value * 0.5 && nextVal < here) {
                continue;
            }

            String step = String.format(Locale.US, "%dx %s → %s",
                    max, shortId(recipe.id()), recipe.output());
            if (stepsSoFar.contains(step)) continue;
            List<String> nextSteps = new ArrayList<>(stepsSoFar);
            nextSteps.add(step);
            search(next, offers, depthLeft - 1, nextSteps, best, candidates, useful);
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