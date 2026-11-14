package de.rcm.ballistic.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** The weather satellite receiver: opens the live satellite picture (clouds, storms, fallout) round you. */
public class WeatherMapItem extends Item {
	public WeatherMapItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			de.rcm.ballistic.ClientHooks.openWeatherMap.run();
		}
		return InteractionResult.SUCCESS;
	}
}
