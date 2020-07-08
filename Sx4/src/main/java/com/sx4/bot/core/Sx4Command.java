package com.sx4.bot.core;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import com.jockie.bot.core.category.ICategory;
import com.jockie.bot.core.command.CommandTrigger;
import com.jockie.bot.core.command.ICommand;
import com.jockie.bot.core.command.impl.AbstractCommand;
import com.jockie.bot.core.command.impl.CommandImpl;
import com.jockie.bot.core.command.impl.DummyCommand;
import com.sx4.bot.annotations.command.AuthorPermissions;
import com.sx4.bot.annotations.command.BotPermissions;
import com.sx4.bot.annotations.command.Canary;
import com.sx4.bot.annotations.command.Donator;
import com.sx4.bot.annotations.command.Examples;
import com.sx4.bot.annotations.command.Redirects;
import com.sx4.bot.config.Config;
import com.sx4.bot.database.Database;
import com.sx4.bot.managers.GiveawayManager;
import com.sx4.bot.managers.ModActionManager;
import com.sx4.bot.managers.MuteManager;
import com.sx4.bot.managers.ReminderManager;
import com.sx4.bot.managers.TempBanManager;
import com.sx4.bot.managers.YouTubeManager;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import okhttp3.OkHttpClient;

public class Sx4Command extends CommandImpl {
	
	public final YouTubeManager youtubeManager = YouTubeManager.get();
	public final ModActionManager modManager = ModActionManager.get();
	public final MuteManager muteManager = MuteManager.get();
	public final TempBanManager banManager = TempBanManager.get();
	public final ReminderManager reminderManager = ReminderManager.get();
	public final GiveawayManager giveawayManager = GiveawayManager.get();
	
	public final OkHttpClient client = Sx4.getClient();
	
	public final Database database = Database.get();
	
	public final Config config = Config.get();
	
	protected boolean donator = false;
	
	protected String[] examples = {};
	protected String[] redirects = {};
	
	protected boolean disabled = false;
	protected String disabledMessage = null;
	
	protected boolean canaryCommand = false;
	
	public Sx4Command(String name) {
		super(name, true);

		super.botDiscordPermissions = EnumSet.of(Permission.MESSAGE_WRITE);
	
		this.checkAnnotations();
	}
	
	public Sx4Command(String name, Method method, Object invoker) {
		super(name, method, invoker);

		super.botDiscordPermissions = EnumSet.of(Permission.MESSAGE_WRITE);
		
		this.checkAnnotations();
	}
	
	public Sx4Command setCanaryCommand(boolean canaryCommand) {
		this.canaryCommand = canaryCommand;
		
		return this;
	}
	
	public boolean isCanaryCommand() {
		return this.canaryCommand;
	}
	
	public void enable() {
		this.disabled = false;
		this.disabledMessage = null;
	}
	
	public void disable() {
		this.disable(null);
	}
	
	public void disable(String message) {
		this.disabled = true;
		this.disabledMessage = message;
	}
	
	public boolean isDisabled() {
		return this.disabled;
	}
	
	public boolean hasDisabledMessage() {
		return this.disabledMessage != null;
	}
	
	public String getDisabledMessage() {
		return this.disabledMessage;
	}
	
	public String[] getRedirects() {
		return this.redirects;
	}
	
	public Sx4Command setRedirects(String... redirects) {
		this.redirects = redirects;
		
		return this;
	}
	
	public String[] getExamples() {
		return this.examples;
	}
	
	public Sx4Command setExamples(String... examples) {
		this.examples = examples;
		
		return this;
	}
	
	public boolean isDonatorCommand() {
		return this.donator;
	}
	
	public Sx4Command setDonatorCommand(boolean donator) {
		this.donator = donator;
		
		return this;
	}
	
	public Sx4Command setAuthorDiscordPermissions(Permission... permissions) {
		return this.setAuthorDiscordPermissions(false, permissions);
	}
	
	public Sx4Command setAuthorDiscordPermissions(boolean overwrite, Permission... permissions) {
		if (overwrite) {
			this.authorDiscordPermissions = permissions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(Arrays.asList(permissions));
		} else {
			this.authorDiscordPermissions.addAll(Arrays.asList(permissions));
		}
		
		return this;
	}

	public EnumSet<Permission> getAuthorDiscordPermissions() {
		return this.authorDiscordPermissions;
	}
	
	public Sx4Command setBotDiscordPermissions(Permission... permissions) {
		return this.setBotDiscordPermissions(false, permissions);
	}
	
	public Sx4Command setBotDiscordPermissions(boolean overwrite, Permission... permissions) {
		if (overwrite) {
			this.botDiscordPermissions = permissions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(Arrays.asList(permissions));
		} else {
			this.botDiscordPermissions.addAll(Arrays.asList(permissions));
		}
		
		return this;
	}
	
	public EnumSet<Permission> getBotDiscordPermissions() {
		return this.botDiscordPermissions;
	}
	
	public List<CommandTrigger> getAllCommandsRecursiveWithTriggers(Message message, String prefix) {
	    List<CommandTrigger> commands = super.getAllCommandsRecursiveWithTriggers(message, prefix);
	    
	    if (this.redirects.length != 0) {
	        List<ICommand> dummyCommands = commands.stream()
	            .map(CommandTrigger::getCommand)
	            .filter(command -> command instanceof DummyCommand)
	            .map(DummyCommand.class::cast)
	            .filter(command -> command.getActualCommand().equals(this))
	            .collect(Collectors.toList());
	        
	        for (String redirect : this.redirects) {
	            commands.add(new CommandTrigger(redirect, this));
	            
	            for (ICommand command : dummyCommands) {
	                commands.add(new CommandTrigger(redirect, command));
	            }
	        }
	    }
	    
	    return commands;
	}
	
	public AbstractCommand setCategory(ICategory category) {
		ICategory old = this.category;
		
		this.category = category;
		
		if (old != null) {
			old.removeCommand(this);
			this.subCommands.forEach(this.category::removeCommand);
			
			ICategory parent = old.getParent();
			if (parent != null) {
				parent.removeCommand(this);
				this.subCommands.forEach(parent::removeCommand);
			}
		}
		
		if (this.category != null) {
			this.category.addCommand(this);
			this.subCommands.forEach(this.category::addCommand);
			
			ICategory parent = this.category.getParent();
			if (parent != null) {
				parent.addCommand(this);
				this.subCommands.forEach(parent::addCommand);
			}
		}
		
		return this;
	}
	
	private void checkAnnotations() {
		if (this.method != null) {
			if (this.method.isAnnotationPresent(Donator.class)) {
				this.donator = this.method.getAnnotation(Donator.class).value();
			}
			
			if (this.method.isAnnotationPresent(Examples.class)) {
				this.examples = this.method.getAnnotation(Examples.class).value();
			}
			
			if (this.method.isAnnotationPresent(Canary.class)) {
				this.canaryCommand = this.method.getAnnotation(Canary.class).value();
			}
			
			if (this.method.isAnnotationPresent(Redirects.class)) {
				this.redirects = this.method.getAnnotation(Redirects.class).value();
			}
			
			AuthorPermissions authorPermissions = this.method.getAnnotation(AuthorPermissions.class);
			if (authorPermissions != null) {
				List<Permission> permissions = Arrays.asList(authorPermissions.permissions());
				if (authorPermissions.overwrite()) {
					this.authorDiscordPermissions = permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
				} else {
					this.authorDiscordPermissions.addAll(permissions);
				}
			}
			
			BotPermissions botPermissions = this.method.getAnnotation(BotPermissions.class);
			if (botPermissions != null) {
				List<Permission> permissions = Arrays.asList(botPermissions.permissions());
				if (botPermissions.overwrite()) {
					this.authorDiscordPermissions = permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
				} else {
					this.authorDiscordPermissions.addAll(permissions);
				}
			}
		}
	}
}
