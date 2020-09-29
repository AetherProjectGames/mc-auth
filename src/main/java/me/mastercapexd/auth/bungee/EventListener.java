package me.mastercapexd.auth.bungee;

import lombok.RequiredArgsConstructor;
import me.mastercapexd.auth.Account;
import me.mastercapexd.auth.AccountFactory;
import me.mastercapexd.auth.Auth;
import me.mastercapexd.auth.IdentifierType;
import me.mastercapexd.auth.PluginConfig;
import me.mastercapexd.auth.storage.AccountStorage;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.UUID;

@RequiredArgsConstructor
public class EventListener implements Listener {

	private final PluginConfig config;
	private final AccountFactory accountFactory;
	private final AccountStorage accountStorage;
	
	@EventHandler
	public void on(LoginEvent event) {
		String name = event.getConnection().getName();
		if (!config.getNamePattern().matcher(name).matches()) {
			String cancelReason = config.getMessages().getMessage("illegal-name-chars");
			event.setCancelReason(new TextComponent(cancelReason));
			event.setCancelled(true);
		}
		
		if (!config.isNameCaseCheckEnabled())
			return;
		
		IdentifierType identifierType = config.getActiveIdentifierType();
		String id = identifierType == IdentifierType.UUID ? event.getConnection().getUniqueId().toString() : name.toLowerCase();

		accountStorage.getAccount(id).thenAccept(account -> {
			if (account == null)
				return;
			if (account.getName().equals(name))
				return;
			String cancelReason = config.getMessages().getMessage("check-name-case-failed").replace("%correct%", account.getName()).replace("%failed%", name);
			event.setCancelReason(new TextComponent(cancelReason));
			event.setCancelled(true);
		});
	}

	@EventHandler
	public void on(PostLoginEvent event) {
		ProxiedPlayer player = event.getPlayer();
		String id = config.getActiveIdentifierType().getId(player);
		
		accountStorage.getAccount(id).thenAccept(account -> {
			if (account == null) {
				@SuppressWarnings("deprecation")
				Account newAccount = accountFactory.createAccount(id, config.getActiveIdentifierType(), player.getUniqueId(), player.getName(), config.getActiveHashType(), null, null, 0, player.getAddress().getHostString(), 0, config.getSessionDurability());
				ServerInfo authServer = config.findServerInfo(config.getAuthServers());
				Auth.addAccount(newAccount);
				Connector.connectOrKick(player, authServer, config.getMessages().getMessage("auth-servers-connection-refused"));
			} else {
				if (account.isSessionActive(config.getSessionDurability())) {
					ServerInfo gameServer = config.findServerInfo(config.getGameServers());
					Auth.removeAccount(id);
					Connector.connectOrKick(player, gameServer, config.getMessages().getMessage("game-servers-connection-refused"));
				} else {
					ServerInfo authServer = config.findServerInfo(config.getAuthServers());
					Auth.addAccount(account);
					Connector.connectOrKick(player, authServer, config.getMessages().getMessage("auth-servers-connection-refused"));
				}
			}
		});
	}
	
	@SuppressWarnings("deprecation")
	@EventHandler
	public void on(ChatEvent event) {
		if (event.isCancelled())
			return;
		ProxiedPlayer player = (ProxiedPlayer) event.getSender();
		if (!Auth.hasAccount(config.getActiveIdentifierType().getId(player)))
			return;
		
		String message = event.getMessage();
		if (!isLoginCommand(message) && !isRegistrationCommand(message) && !message.toLowerCase().startsWith("/confirm") && !message.toLowerCase().startsWith("/recovery")) {
			player.sendMessage(config.getMessages().getMessage("disabled-command"));
			event.setCancelled(true);
		}
	}

	private boolean isLoginCommand(String message) {
		return message.toLowerCase().startsWith("/l ") || message.toLowerCase().startsWith("/login ");
	}

	private boolean isRegistrationCommand(String message) {
		return message.toLowerCase().startsWith("/reg ") || message.toLowerCase().startsWith("/register ");
	}
	
	@EventHandler
	public void on(PlayerDisconnectEvent event) {
		String id = config.getActiveIdentifierType().getId(event.getPlayer());
		if (Auth.hasAccount(id)) {
			Auth.removeAccount(id);
			return;
		}
		
		if (Auth.getEmailConfirmationEntry(id) != null)
			Auth.removeEmailConfirmationEntry(id);
		
		if (Auth.hasRecoveryRequest(id))
			Auth.removeRecoveryRequest(id);
		
		accountStorage.getAccount(id).thenAccept(account -> {
			account.setLastQuitTime(System.currentTimeMillis());
			accountStorage.saveOrUpdateAccount(account);
		});
	}
}