package store.cadera.cdrbounty.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Objects;

public final class VaultEconomyAdapter {
    private final Economy economy;

    private VaultEconomyAdapter(Economy economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    public static VaultEconomyAdapter hook(JavaPlugin plugin) {
        RegisteredServiceProvider<Economy> registration = plugin.getServer()
                .getServicesManager()
                .getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            throw new IllegalStateException("Vault is installed but no Economy provider is registered");
        }
        return new VaultEconomyAdapter(registration.getProvider());
    }

    public boolean has(OfflinePlayer player, BigDecimal amount) {
        return economy.has(player, amount.doubleValue());
    }

    public Result withdraw(OfflinePlayer player, BigDecimal amount) {
        EconomyResponse response = economy.withdrawPlayer(player, amount.doubleValue());
        return new Result(response.transactionSuccess(), response.errorMessage);
    }

    public Result deposit(OfflinePlayer player, BigDecimal amount) {
        EconomyResponse response = economy.depositPlayer(player, amount.doubleValue());
        return new Result(response.transactionSuccess(), response.errorMessage);
    }

    public BigDecimal balance(OfflinePlayer player) {
        return BigDecimal.valueOf(economy.getBalance(player));
    }

    public String format(BigDecimal amount) {
        return economy.format(amount.doubleValue());
    }

    public record Result(boolean success, String error) {
        public String safeError() {
            return error == null || error.isBlank() ? "economy provider rejected transaction" : error;
        }
    }
}
