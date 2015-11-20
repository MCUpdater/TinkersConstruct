package slimeknights.tconstruct.tools.modifiers;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import slimeknights.tconstruct.library.modifiers.ModifierAspect;
import slimeknights.tconstruct.library.modifiers.ModifierNBT;
import slimeknights.tconstruct.library.tools.ToolNBT;
import slimeknights.tconstruct.library.traits.AbstractTrait;
import slimeknights.tconstruct.library.utils.TagUtil;
import slimeknights.tconstruct.library.utils.ToolHelper;

/**
 * This trait provides long-term benefits for using the same tool.
 * Basically it makes your tool get better over time as you use it.
 *
 * Detailed explanation: The tool gets a "stat pool" to distribute over time.
 * Stats are added to the pool on repairing, mining and attacking.
 * Values are dimishing depending on overall stats of the tool. So the growth slows down depending on your overall tool stats.
 */
public class ToolGrowth extends AbstractTrait {

  public static final String TAG_Pool = "StatPool";
  public static final String TAG_Bonus = "StatBonus";

  protected static float DURABILITY_COEFFICIENT = 0.04f; // % of amount repaired
  protected static float SPEED_INCREMENT = 0.05f; // flat
  protected static float ATTACK_INCREMENT = 0.03f; // flat

  // how much the value gets increased in one distribution step
  protected static int DURABILITY_STEP = 1;
  protected static float SPEED_STEP = 0.01f;
  protected static float ATTACK_STEP = 0.01f;

  public ToolGrowth() {
    super("toolgrowth", EnumChatFormatting.WHITE);

    this.addAspects(new ModifierAspect.SingleAspect(this));
  }

  /* Modifier management */

  @Override
  public void applyEffect(NBTTagCompound rootCompound, NBTTagCompound modifierTag) {
    super.applyEffect(rootCompound, modifierTag);
    // called on tool loading only
    // we just apply the saved bonus stats
    ToolNBT data = TagUtil.getToolStats(rootCompound);
    GrowthNBT bonus = getBonus(rootCompound);

    data.durability += bonus.durability;
    data.speed += bonus.speed;
    data.attack += bonus.attack;

    TagUtil.setToolTag(rootCompound, data.get());
  }

  @Override
  public boolean isHidden() {
    return true;
  }

  /* Actual logic */

  // Distributing stats from the pool
  @Override
  public void onUpdate(ItemStack tool, World world, Entity entity, int itemSlot, boolean isSelected) {
    if(entity instanceof FakePlayer || world.isRemote) {
      return;
    }
    // we only distribute every minute or so
    if(random.nextFloat() > 0.0006f) {
      return;
    }

    // we don't update if the player is currently breaking a block because that'd reset it
    if(entity instanceof EntityPlayerMP) {
      if(((EntityPlayerMP) entity).theItemInWorldManager.isDestroyingBlock) {
        return;
      }
    }
    else if(entity instanceof EntityPlayerSP) {
      if(Minecraft.getMinecraft().playerController.isHittingBlock) {
        return;
      }
    }

    // get stat pool
    NBTTagCompound root = TagUtil.getTagSafe(tool);
    GrowthNBT pool = getPool(root);
    GrowthNBT bonus = getBonus(root);

    ToolNBT data = TagUtil.getToolStats(tool);

    // pick one
    int choice = random.nextInt(3);
    // durability
    if(choice == 0) {
      if(pool.durability >= DURABILITY_STEP) {
        pool.durability -= DURABILITY_STEP;
        bonus.durability += DURABILITY_STEP;
        data.durability += DURABILITY_STEP;
      }
    }
    // speed
    else if(choice == 1) {
      if(pool.speed >= SPEED_STEP) {
        pool.speed -= SPEED_STEP;
        bonus.speed += SPEED_STEP;
        data.speed += SPEED_STEP;
      }
    }
    // attack
    else if(choice == 2) {
      if(pool.attack >= ATTACK_STEP) {
        pool.attack -= ATTACK_STEP;
        bonus.attack += ATTACK_STEP;
        data.attack += ATTACK_STEP;
      }
    }

    // update stats on the tool
    TagUtil.setToolTag(tool, data.get());

    // write pool and bonus back onto the tool
    setBonus(root, bonus);
    setPool(root, pool);
  }

  // Filling the pool with durability
  @Override
  public void onRepair(ItemStack tool, int amount) {
    // read data from tool
    NBTTagCompound root = TagUtil.getTagSafe(tool);
    GrowthNBT pool = getPool(root);
    int totalDurability = ToolHelper.getDurability(tool);
    float famount = amount;

    // cap the amount if it's more than what gets repaired
    if(famount > totalDurability - ToolHelper.getCurrentDurability(tool)) {
      famount = totalDurability - ToolHelper.getCurrentDurability(tool);
    }

    // add a bit of random
    famount *= 0.975f + random.nextFloat()*0.05f;

    // calculate stats to add to pool. Baseline: 1000 durability
    int extra = (int) (calcDimishingReturns(totalDurability, 1000f) * famount * DURABILITY_COEFFICIENT);

    pool.durability += 1 + extra;

    // write data back onto the tool
    setPool(root, pool);
  }

  // Filling the pool with speed
  @Override
  public void afterBlockBreak(ItemStack tool, World world, Block block, BlockPos pos, EntityLivingBase player, boolean wasEffective) {
    if(player instanceof FakePlayer || world.isRemote) {
      return;
    }
    // 10% chance to gain stats on effective blockbreak
    if(!wasEffective || random.nextFloat() > 0.1f) {
      return;
    }

    // read data from tool
    NBTTagCompound root = TagUtil.getTagSafe(tool);
    GrowthNBT pool = getPool(root);
    float totalSpeed = ToolHelper.getMiningSpeed(tool);

    // calculate stats to add to pool. Baseline: 5
    float extra = calcDimishingReturns(totalSpeed, 5f) * SPEED_INCREMENT;

    pool.speed += extra + 0.005f;

    // write data back onto the tool
    setPool(root, pool);
  }

  // Filling the pool with damage
  @Override
  public void afterHit(ItemStack tool, EntityLivingBase player, EntityLivingBase target, float damageDealt, boolean wasCritical, boolean wasHit) {
    if(player instanceof FakePlayer || player.worldObj.isRemote) {
      return;
    }
    // 10% chance on hit to gain stats
    if(random.nextFloat() > 0.1f) {
      return;
    }

    // read data from tool
    NBTTagCompound root = TagUtil.getTagSafe(tool);
    GrowthNBT pool = getPool(root);
    float totalSpeed = ToolHelper.getMiningSpeed(tool);

    // calculate stats to add to pool. Baseline: 10 (= 5 hearts)
    float extra = calcDimishingReturns(totalSpeed, 10f) * ATTACK_INCREMENT;

    pool.attack += extra + 0.005f;

    // write data back onto the tool
    setPool(root, pool);
  }

  // Filling the pool with dimishing returns!
  protected float calcDimishingReturns(float value, float baseline) {
    return 2f / (1f + (value / baseline) * (value / baseline));
  }



  protected GrowthNBT getPool(NBTTagCompound root) {
    return getStats(root, TAG_Pool);
  }

  protected void setPool(NBTTagCompound root, GrowthNBT data) {
    setStats(root, data, TAG_Pool);
  }

  protected GrowthNBT getBonus(NBTTagCompound root) {
    return getStats(root, TAG_Bonus);
  }

  protected void setBonus(NBTTagCompound root, GrowthNBT data) {
    setStats(root, data, TAG_Bonus);
  }

  private GrowthNBT getStats(NBTTagCompound root, String key) {
    return ModifierNBT.readTag(TagUtil.getTagSafe(TagUtil.getExtraTag(root), key), GrowthNBT.class);
  }

  private void setStats(NBTTagCompound root, GrowthNBT data, String key) {
    NBTTagCompound extra = TagUtil.getExtraTag(root);
    NBTTagCompound tag = new NBTTagCompound();
    data.write(tag);
    extra.setTag(key, tag);
    TagUtil.setExtraTag(root, extra);
  }

  public static class GrowthNBT extends ModifierNBT {

    // statpool
    public int durability;
    public float attack;
    public float speed;

    @Override
    public void read(NBTTagCompound tag) {
      super.read(tag);
      durability = tag.getInteger("durability");
      attack = tag.getFloat("attack");
      speed = tag.getFloat("speed");
    }

    @Override
    public void write(NBTTagCompound tag) {
      super.write(tag);
      tag.setInteger("durability", durability);
      tag.setFloat("attack", attack);
      tag.setFloat("speed", speed);
    }
  }
}