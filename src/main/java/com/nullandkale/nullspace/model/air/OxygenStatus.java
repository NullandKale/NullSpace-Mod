package com.nullandkale.nullspace.model.air;

/** Actionable air status; healthy cabin air leaves the action bar free. */
public final class OxygenStatus
{
    public static String text(int meter, int local, boolean cabin, boolean supplied, boolean suited, int canister)
    {
        int percent = Math.clamp(meter / 10, 0, 100);
        if (percent <= 20)
            return "LOW OXYGEN " + percent + "% - return to air";
        if (!cabin)
            return supplied || suited && canister > 0 ? "Suit air " + percent + "% | Canister " + canister + "%" : "VACUUM - return to air | Oxygen " + percent + "%";
        if (meter < 950 && local * 10 > meter)
            return "Oxygen recovering: " + percent + "%";
        if (local < 50)
            return "Room air " + local + "% | Oxygen " + percent + "% - check air supply";
        return "";
    }

    private OxygenStatus()
    {
    }
}
