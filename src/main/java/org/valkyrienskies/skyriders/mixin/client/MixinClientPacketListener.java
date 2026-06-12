package org.valkyrienskies.skyriders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.skyriders.client.SkyridersModClient;
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @WrapOperation(
            method = "handleSetEntityPassengersPacket",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;getTranslatedKeyMessage()Lnet/minecraft/network/chat/Component;")
    )
    private Component redirectTranslation(KeyMapping instance, Operation<Component> original, @Local(ordinal = 0) Entity entity) {
        if (!(entity instanceof BikeSeatEntity)) return original.call(instance);
        return SkyridersModClient.INSTANCE.getBikeDismountKey().getTranslatedKeyMessage();
    }
}
