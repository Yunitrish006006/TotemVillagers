package dev.totem.villagers.client;

import dev.totem.villagers.woodcutter.TotemVillagerMenus;
import dev.totem.villagers.woodcutter.WoodcutterMenu;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Registers the screen through Fabric Menu API's runtime class-tweaked bridge.
 * The 26.2 mappings retain {@code MenuScreens.register} as private during
 * compilation even though Fabric makes it public before client initialisation.
 */
final class WoodcutterScreenRegistration {
    private WoodcutterScreenRegistration() {
    }

    static void register() {
        try {
            Class<?> constructorType = Class.forName("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
            Method register = MenuScreens.class.getDeclaredMethod("register", MenuType.class, constructorType);
            register.setAccessible(true);
            Object constructor = Proxy.newProxyInstance(constructorType.getClassLoader(), new Class<?>[]{constructorType}, factory());
            register.invoke(null, TotemVillagerMenus.WOODCUTTER, constructor);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not register the Woodcutter screen", exception);
        }
    }

    private static InvocationHandler factory() {
        return (proxy, method, arguments) -> switch (method.getName()) {
            case "create" -> new WoodcutterScreen((WoodcutterMenu) arguments[0],
                    (Inventory) arguments[1], (Component) arguments[2]);
            case "toString" -> "Totem Woodcutter screen factory";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new UnsupportedOperationException("Unexpected MenuScreens factory method: " + method.getName());
        };
    }
}
