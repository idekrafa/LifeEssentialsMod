package com.lifeessentials.mixin.client;

import com.lifeessentials.client.sound.VolumeDucker;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the phone-music duck factor to every in-game sound. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
			at = @At("RETURN"), cancellable = true)
	private void lifeessentials$duckVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		float factor = VolumeDucker.factorFor(sound.getSource());
		if (factor < 0.999f) {
			cir.setReturnValue(cir.getReturnValueF() * factor);
		}
	}
}
