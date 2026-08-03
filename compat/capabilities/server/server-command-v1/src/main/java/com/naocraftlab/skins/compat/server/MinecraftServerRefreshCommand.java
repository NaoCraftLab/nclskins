package com.naocraftlab.skins.compat.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.ServerRefreshCommandProtocol;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;


public final class MinecraftServerRefreshCommand {
    private MinecraftServerRefreshCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher").register(
                Commands.literal(ServerRefreshCommandProtocol.ROOT_COMMAND)
                        .requires(MinecraftServerRefreshCommand::canRefresh)
                        .then(Commands.literal(ServerRefreshCommandProtocol.REFRESH_COMMAND)
                                .executes(context -> refresh(context.getSource()))));
    }

    private static boolean canRefresh(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return ServerRefreshCommandProtocol.eligible(false, false, false);
        }
        Optional<MinecraftServerAppearanceService> service =
                MinecraftServerAppearanceService.registered(source.getServer());
        return ServerRefreshCommandProtocol.eligible(
                true,
                service.isPresent(),
                service.map(registered -> registered.eligible(player)).orElse(false));
    }

    private static int refresh(CommandSourceStack source) throws CommandSyntaxException {
        MinecraftServerAppearanceService service =
                MinecraftServerAppearanceService.registered(source.getServer()).orElse(null);
        if (service == null) {
            return ServerRefreshCommandProtocol.FAILURE;
        }
        Admission admission = service.request(source.getPlayerOrException()).admission();
        return ServerRefreshCommandProtocol.result(admission);
    }
}
