package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.ClientExecutor;
import java.util.Objects;
import net.minecraft.client.Minecraft;


public final class MinecraftClientExecutor implements ClientExecutor {
    @Override
    public boolean isClientThread() {
        return Minecraft.getInstance().isSameThread();
    }

    @Override
    public void execute(Runnable action) {
        Minecraft.getInstance().execute(Objects.requireNonNull(action, "action"));
    }
}
