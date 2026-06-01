package org.valkyrienskies.skyriders.mixinduck;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public interface BikeCameraDuck {
    float skyriders$getZRot();

    boolean skyriders$updateBikeLook(Entity entity, float partialTick);

    Vec3 skyriders$getBikeLookVector();
}
