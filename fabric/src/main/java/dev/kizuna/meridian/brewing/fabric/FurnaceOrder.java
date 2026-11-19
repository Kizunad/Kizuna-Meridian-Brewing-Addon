package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import java.util.UUID;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 一次选方绑定药种、剂型和出元人；开熬时把这一份锁给当前批次。 */
record FurnaceOrder(BrewKind kind, BrewForm form, UUID owner) {
    void write(ValueOutput out) {
        out.putString("recipe", kind.path());
        out.putString("form", form.name());
        out.putString("owner", owner.toString());
    }

    static FurnaceOrder read(ValueInput input) {
        return new FurnaceOrder(RecipeKnowledge.kind(input.getStringOr("recipe", "")).orElseThrow(),
                BrewForm.valueOf(input.getStringOr("form", "")), UUID.fromString(input.getStringOr("owner", "")));
    }
}
