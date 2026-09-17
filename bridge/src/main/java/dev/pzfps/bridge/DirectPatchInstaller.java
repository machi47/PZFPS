package dev.pzfps.bridge;

import java.lang.instrument.Instrumentation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import me.zed_0xff.zombie_buddy.Loader;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Installs the exact PZFPS hooks and reports the classes actually retransformed. */
public final class DirectPatchInstaller {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Class<?>[] ADVICE_TYPES = {
        WorldRenderPatch.class,
        PlayerUpdatePatch.class,
        InputMovePatch.class,
        AimVectorPatch.class,
        AimStatePatch.class,
        StrafingPatch.class,
        KeyboardInputPatch.Down.class,
        KeyboardInputPatch.Pressed.class,
        MouseUpdatePatch.class,
        MouseInputPatch.Down.class,
        MouseInputPatch.Pressed.class,
        MouseInputPatch.UiCheck.class
    };
    private static volatile ClassFileLocator adviceLocator;

    private DirectPatchInstaller() {}

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        Instrumentation instrumentation = Loader.getInstrumentation();
        if (instrumentation == null) {
            INSTALLED.set(false);
            throw new IllegalStateException("ZombieBuddy instrumentation is unavailable");
        }
        adviceLocator = loadAdviceLocator();

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new HookListener())
                .type(namedOneOf(
                        "zombie.iso.IsoWorld",
                        "zombie.characters.IsoGameCharacter",
                        "zombie.characters.IsoPlayer",
                        "zombie.input.GameKeyboard",
                        "zombie.input.Mouse"))
                .transform(DirectPatchInstaller::transform)
                .installOn(instrumentation);
        System.out.println("[PZFPS] direct hook installer active");
    }

    private static DynamicType.Builder<?> transform(
            DynamicType.Builder<?> builder,
            TypeDescription type,
            ClassLoader loader,
            JavaModule module,
            java.security.ProtectionDomain protectionDomain) {
        return switch (type.getName()) {
            case "zombie.iso.IsoWorld" -> {
                ElementMatcher.Junction<MethodDescription> render =
                        named("render").and(takesArguments(0));
                requireOneTarget(type, render, "render()V");
                yield builder.visit(advice(WorldRenderPatch.class).on(render));
            }
            case "zombie.characters.IsoPlayer" -> {
                ElementMatcher.Junction<MethodDescription> update =
                        named("update").and(takesArguments(0));
                ElementMatcher.Junction<MethodDescription> movement = inputMoveMatcher();
                ElementMatcher.Junction<MethodDescription> aim = aimMatcher();
                ElementMatcher.Junction<MethodDescription> calculateAim = calculateAimMatcher();
                ElementMatcher.Junction<MethodDescription> setAngleFromAim = setAngleFromAimMatcher();
                requireOneTarget(type, update, "update()V");
                requireOneTarget(
                        type,
                        movement,
                        "getInputMoveVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
                requireOneTarget(
                        type,
                        aim,
                        "getAimVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
                requireOneTarget(
                        type,
                        calculateAim,
                        "calculateAimVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
                requireOneTarget(type, setAngleFromAim, "setAngleFromAim()V");
                yield builder
                        .visit(advice(PlayerUpdatePatch.class).on(update))
                        .visit(advice(InputMovePatch.class).on(movement))
                        .visit(advice(AimVectorPatch.class).on(aim.or(calculateAim)))
                        .visit(advice(AimStatePatch.class).on(setAngleFromAim));
            }
            case "zombie.characters.IsoGameCharacter" -> {
                ElementMatcher.Junction<MethodDescription> strafing =
                        named("isStrafing").and(takesArguments(0));
                requireOneTarget(type, strafing, "isStrafing()Z");
                yield builder.visit(advice(StrafingPatch.class).on(strafing));
            }
            case "zombie.input.GameKeyboard" -> {
                ElementMatcher.Junction<MethodDescription> down =
                        named("isKeyDown").and(takesArguments(String.class));
                ElementMatcher.Junction<MethodDescription> pressed =
                        named("isKeyPressed").and(takesArguments(String.class));
                requireOneTarget(type, down, "isKeyDown(Ljava/lang/String;)Z");
                requireOneTarget(type, pressed, "isKeyPressed(Ljava/lang/String;)Z");
                yield builder
                        .visit(advice(KeyboardInputPatch.Down.class).on(down))
                        .visit(advice(KeyboardInputPatch.Pressed.class).on(pressed));
            }
            case "zombie.input.Mouse" -> {
                ElementMatcher.Junction<MethodDescription> update =
                        named("update").and(takesArguments(0));
                ElementMatcher.Junction<MethodDescription> down =
                        named("isButtonDown").and(takesArguments(int.class));
                ElementMatcher.Junction<MethodDescription> pressed =
                        named("isButtonPressed").and(takesArguments(int.class));
                ElementMatcher.Junction<MethodDescription> uiCheck =
                        named("isButtonDownUICheck").and(takesArguments(int.class));
                requireOneTarget(type, update, "update()V");
                requireOneTarget(type, down, "isButtonDown(I)Z");
                requireOneTarget(type, pressed, "isButtonPressed(I)Z");
                requireOneTarget(type, uiCheck, "isButtonDownUICheck(I)Z");
                yield builder
                        .visit(advice(MouseUpdatePatch.class).on(update))
                        .visit(advice(MouseInputPatch.Down.class).on(down))
                        .visit(advice(MouseInputPatch.Pressed.class).on(pressed))
                        .visit(advice(MouseInputPatch.UiCheck.class).on(uiCheck));
            }
            default -> builder;
        };
    }

    static ElementMatcher.Junction<MethodDescription> inputMoveMatcher() {
        return named("getInputMoveVector")
                .and(takesArguments(1))
                .and(takesArgument(0, named("zombie.iso.Vector2")));
    }

    static ElementMatcher.Junction<MethodDescription> aimMatcher() {
        return named("getAimVector")
                .and(takesArguments(1))
                .and(takesArgument(0, named("zombie.iso.Vector2")));
    }

    static ElementMatcher.Junction<MethodDescription> calculateAimMatcher() {
        return named("calculateAimVector")
                .and(takesArguments(1))
                .and(takesArgument(0, named("zombie.iso.Vector2")));
    }

    static ElementMatcher.Junction<MethodDescription> setAngleFromAimMatcher() {
        return named("setAngleFromAim").and(takesArguments(0));
    }

    static void requireOneTarget(
            TypeDescription type,
            ElementMatcher<? super MethodDescription> matcher,
            String expectedDescriptor) {
        int matches = type.getDeclaredMethods().filter(matcher).size();
        if (matches != 1) {
            throw new IllegalStateException(
                    "expected exactly one hook target " + type.getName() + "."
                            + expectedDescriptor + ", found " + matches);
        }
        System.out.printf(
                "[PZFPS] verified hook target class=%s method=%s%n",
                type.getName(), expectedDescriptor);
    }

    private static Advice advice(Class<?> type) {
        ClassFileLocator locator = adviceLocator;
        if (locator == null) throw new IllegalStateException("advice class locator is not initialized");
        return Advice.to(type, locator);
    }

    private static ClassFileLocator loadAdviceLocator() {
        String configured = System.getProperty("pzfps.bridgeJar", "").trim();
        if (configured.isEmpty()) {
            throw new IllegalStateException("pzfps.bridgeJar is absent");
        }
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jar = new JarFile(Path.of(configured).toFile())) {
            for (Class<?> type : ADVICE_TYPES) {
                String entryName = type.getName().replace('.', '/') + ".class";
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry == null) throw new IOException("missing advice entry " + entryName);
                try (InputStream input = jar.getInputStream(entry)) {
                    classes.put(type.getName(), input.readAllBytes());
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot load advice classes from " + configured, error);
        }
        return new ClassFileLocator.Simple(classes);
    }

    private static final class HookListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onTransformation(
                TypeDescription type,
                ClassLoader loader,
                JavaModule module,
                boolean loaded,
                DynamicType dynamicType) {
            System.out.printf(
                    "[PZFPS] direct hook transformed class=%s loaded=%s%n",
                    type.getName(), loaded);
        }

        @Override
        public void onError(
                String typeName,
                ClassLoader loader,
                JavaModule module,
                boolean loaded,
                Throwable error) {
            System.err.printf(
                    "[PZFPS] direct hook failure class=%s loaded=%s error=%s%n",
                    typeName, loaded, error);
            error.printStackTrace(System.err);
        }
    }
}
