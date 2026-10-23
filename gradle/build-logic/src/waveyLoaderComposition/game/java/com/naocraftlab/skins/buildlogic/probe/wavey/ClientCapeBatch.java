package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.waveycapes.support.SupportManager;
import dev.tr7zw.waveycapes.versionless.ModBase;
import dev.tr7zw.waveycapes.versionless.config.Config;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.*;

public final class ClientCapeBatch {
    private static int cases;
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void runCase(String name, CaseLifecycle.Action action) throws Exception {
        var original = ModBase.config;
        var supports = List.copyOf(SupportManager.getSupportedMods());
        var singleton = Minecraft.class.getDeclaredField("instance"); singleton.setAccessible(true);
        Object previous = singleton.get(null);
        String wrong = System.getProperty("nclskins.wavey.wrongRaw");
        ModBase.config = new Config();
        ModeCapeCases.reset(); CapePublicationProbe.reset();
        CaseLifecycle.execute(() -> {
            action.run();
            cases++;
            System.out.println("CANONICAL_CASE_PASS " + name);
        }, () -> {
            try {
                check(singleton.get(null) == previous, "SYNTHETIC_SINGLETON_LEAK");
                check(List.copyOf(SupportManager.getSupportedMods()).equals(supports), "SUPPORT_REGISTRATION_LEAK");
                ModeCapeCases.verifyClosed();
            } finally {
                try {
                    singleton.set(null, previous);
                    SupportManager.getSupportedMods().removeIf(support -> !supports.contains(support));
                    supports.forEach(support -> { if (!SupportManager.getSupportedMods().contains(support)) SupportManager.getSupportedMods().add(support); });
                    ModeCapeCases.reset(); CapePublicationProbe.reset();
                    resetExtraction();
                } finally {
                    ModBase.config = original;
                    if (wrong == null) System.clearProperty("nclskins.wavey.wrongRaw"); else System.setProperty("nclskins.wavey.wrongRaw", wrong);
                }
            }
            check(CapePublicationProbe.lastRawActor() == null && CapePublicationProbe.lastRawState() == null, "CASE_REFERENCE_RESET");
            ModeCapeCases.verifyReset();
            System.out.println("CANONICAL_CASE_RESET_PASS " + name);
        });
    }
    public static void run(Path root) throws Exception {
        cases = 0;
        var environment = MixinEnvironment.getCurrentEnvironment();
        check(environment.getSide() == MixinEnvironment.Side.CLIENT && environment.getPhase() == MixinEnvironment.Phase.DEFAULT, "CLIENT_MIXIN_PHASE");
        Set<String> configs = new HashSet<>(environment.getMixinConfigs());
        Set<String> expectedConfigs = Set.of(System.getProperty("nclskins.wavey.configs").split(","));
        check(configs.containsAll(expectedConfigs), "NORMAL_CONFIG_SET");
        check(WaveyProducerFixture.class.getModule() == CapeProviderCoordinator.class.getModule()
                && "nclskins".equals(WaveyProducerFixture.class.getModule().getName()), "RUNTIME_FIXTURE_MODULE");
        System.out.println("CANONICAL_NORMAL_CONFIGS_MODULE_PASS " + configs);
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        System.out.println("CANONICAL_BOOTSTRAP_PASS; SYNTHETIC constructor-free Minecraft host; NOT client lifecycle/GPU");
        String profile = System.getProperty("nclskins.wavey.definitionProfile", "positive");
        if (profile.equals("guards")) {
            runCase("guard-partial-registration", () -> CapeRegistrationFailureProbe.main(new String[0]));
            runCase("guard-callback-linkage", () -> CapeCallbackLinkageProbe.runCase(new String[]{root.resolve("callback").toString()}));
            runCase("guard-closed-legacy", () -> CapeClosedLegacyProbe.main(new String[0]));
            return;
        }
        if (!profile.equals("positive")) {
            runCase(profile, () -> {
                try {
                    if (profile.equals("missing-world")) CapePublicationProbe.runCase(new String[]{root.resolve(profile).toString(), "SMOOTH"});
                    else if (profile.equals("missing-extraction")) extraction(root.resolve(profile));
                    else throw new IllegalArgumentException("UNKNOWN_DEFINITION_PROFILE");
                    throw new IllegalStateException("MISSING_PUBLICATION_ACCEPTED");
                } catch (AssertionError expected) {
                    String reason = profile.equals("missing-world") ? "WORLD_PUBLICATION_MISSING" : "EXTRACTION_PUBLICATION_MISSING";
                    check(expected.getMessage().startsWith(reason) && CapePublicationProbe.lastRawState() != null, "DEFINITION_SENSITIVITY_NOT_BEHAVIORAL");
                    System.out.println("CANONICAL_EXPECTED_BEHAVIOR_RED " + profile);
                }
            });
            return;
        }
        runCase("native-enchantment", ClientCapeBatch::nativeEnchantment);
        for (String style : List.of("SMOOTH", "BLOCKY")) runCase("world-" + style,
                () -> CapePublicationProbe.runCase(new String[]{root.resolve("world-" + style).toString(), style}));
        ModeCapeCases.run(root);
        if (Boolean.getBoolean("nclskins.wavey.extraction")) runCase("extraction-baked-public", () -> {
            try {
                Class.forName("com.naocraftlab.skins.buildlogic.probe.wavey.ExtractionBakedPreviewProbe")
                        .getMethod("runCase", String[].class).invoke(null, (Object) new String[]{root.resolve("extraction-baked").toString()});
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                if (failure.getCause() instanceof Exception exception) throw exception;
                throw failure;
            }
        });
        runCase("wrong-raw-sensitivity", () -> {
            System.setProperty("nclskins.wavey.wrongRaw", "true");
            try {
                CapePublicationProbe.runCase(new String[]{root.resolve("wrong-raw").toString(), "SMOOTH"});
                throw new IllegalStateException("WRONG_RAW_ACCEPTED");
            } catch (AssertionError expected) {
                check(expected.getMessage().startsWith("WORLD_PUBLICATION_MISSING") && CapePublicationProbe.lastRawState() != null,
                        "SENSITIVITY_NOT_BEHAVIORAL");
                System.out.println("CANONICAL_EXPECTED_BEHAVIOR_RED wrong-raw");
            }
        });
        runCase("guard-no-support-sensitivity", () -> {
            try {
                CapeGuardProbe.run(root.resolve("guard-no-support"), true, ModeCapeCases::consumeGuard, System.getProperty("nclskins.wavey.mode").equals("combined") ? 1 : 2);
                throw new IllegalStateException("NO_SUPPORT_ACCEPTED");
            } catch (AssertionError expected) {
                check("NATIVE_BRIDGE_FOIL_MISSING".equals(expected.getMessage()), "NO_SUPPORT_NOT_BEHAVIORAL");
            }
        });
        runCase("guard-bridge-contract", () -> CapeGuardProbe.run(root.resolve("guard-bridge"), false, ModeCapeCases::consumeGuard, System.getProperty("nclskins.wavey.mode").equals("combined") ? 1 : 2));
        System.out.println("CANONICAL_CLIENT_BATCH_PASS cases=" + cases + "; one render-thread init; fresh producer/storage/consumer; isolated definitions retained separately");
    }
    public static void extraction(Path root, String... style) throws Exception {
        invokeExtraction("runCase", new Class<?>[]{String[].class}, (Object) new String[]{root.toString(), style.length == 0 ? "SMOOTH" : style[0]});
    }
    private static void resetExtraction() throws Exception {
        if (Boolean.getBoolean("nclskins.wavey.extraction")) invokeExtraction("reset", new Class<?>[0]);
    }
    private static void invokeExtraction(String method, Class<?>[] parameters, Object... args) throws Exception {
        try { Class.forName("com.naocraftlab.skins.buildlogic.probe.wavey.ExtractionPublicationProbe").getMethod(method, parameters).invoke(null, args); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }
    private static void nativeEnchantment() throws Exception {
        var plain = NativeEnchantmentInput.elytra();
        var enchanted = NativeEnchantmentInput.elytra();
        var definition = Enchantment.definition(HolderSet.direct(Items.ELYTRA.builtInRegistryHolder()), 1, 1,
                Enchantment.constantCost(1), Enchantment.constantCost(1), 1, EquipmentSlotGroup.CHEST);
        var enchantment = new Enchantment(Component.literal("Synthetic test enchantment"), definition,
                HolderSet.direct(), DataComponentMap.EMPTY);
        enchanted.enchant(Holder.direct(enchantment), 1);
        checkNativeEnchantment(plain, enchanted);
        check(plain != enchanted && plain.getItem() == Items.ELYTRA && enchanted.getItem() == Items.ELYTRA
                && plain.is(Items.ELYTRA) && enchanted.is(Items.ELYTRA)
                && plain.getCount() == 1 && enchanted.getCount() == 1
                && plain.getComponents() != enchanted.getComponents(), "NATIVE_ELYTRA_INPUT_IDENTITY");
        var copy = enchanted.copy();
        check(copy != enchanted && copy.getComponents() != enchanted.getComponents(), "NATIVE_ELYTRA_COPY_IDENTITY");
        checkNativeEnchantment(plain, copy);
        copy.set(DataComponents.ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        rejectNativeEnchantment(plain, copy, "copy-mutation-isolated");
        checkNativeEnchantment(plain, enchanted);
        rejectNativeEnchantment(plain, NativeEnchantmentInput.elytra(), "skipped-mutation");
        var missing = NativeEnchantmentInput.elytra();
        missing.remove(DataComponents.ENCHANTMENTS);
        missing.enchant(Holder.direct(enchantment), 1);
        check(missing.get(DataComponents.ENCHANTMENTS) == null && !missing.isEnchanted() && !missing.hasFoil(),
                "MISSING_ENCHANTMENTS_NATIVE_NO_OP");
        rejectNativeEnchantment(plain, missing, "missing-enchantments");
        var overrideOnly = NativeEnchantmentInput.elytra();
        overrideOnly.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        check(!overrideOnly.isEnchanted() && overrideOnly.hasFoil(), "OVERRIDE_ONLY_CONTROL");
        rejectNativeEnchantment(plain, overrideOnly, "override-only");
        try {
            Class.forName("com.naocraftlab.skins.buildlogic.probe.wavey.NativeElytraConsumptionProbe")
                    .getMethod("consume", ItemStack.class, ItemStack.class).invoke(null, plain, enchanted);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
        System.out.println("CANONICAL_NATIVE_ENCHANTMENT_PASS plain=false,false; component-backed=true,true; override=absent; synthetic own enchantment definition");
    }
    private static void checkNativeEnchantment(ItemStack plain, ItemStack enchanted) {
        check(!plain.isEnchanted() && !plain.hasFoil() && enchanted.isEnchanted() && enchanted.hasFoil()
                && plain.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null
                && enchanted.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null, "NATIVE_COMPONENT_ENCHANTMENT");
    }
    private static void rejectNativeEnchantment(ItemStack plain, ItemStack candidate, String name) {
        try {
            checkNativeEnchantment(plain, candidate);
        } catch (AssertionError expected) {
            check("NATIVE_COMPONENT_ENCHANTMENT".equals(expected.getMessage()), "WRONG_COMPONENT_CONTROL_FAILURE");
            System.out.println("CANONICAL_EXPECTED_COMPONENT_RED " + name);
            return;
        }
        throw new AssertionError("COMPONENT_CONTROL_ACCEPTED " + name);
    }
}
