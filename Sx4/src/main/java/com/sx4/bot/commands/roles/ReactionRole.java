package com.sx4.bot.commands.roles;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.jockie.bot.core.argument.Argument;
import com.jockie.bot.core.command.Command;
import com.jockie.bot.core.command.Command.AuthorPermissions;
import com.jockie.bot.core.command.Command.BotPermissions;
import com.jockie.bot.core.command.Command.Cooldown;
import com.jockie.bot.core.command.impl.CommandEvent;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.sx4.bot.annotations.argument.ExcludeUpdate;
import com.sx4.bot.annotations.command.Examples;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import com.sx4.bot.entities.argument.All;
import com.sx4.bot.entities.argument.MessageArgument;
import com.sx4.bot.entities.argument.UpdateType;
import com.sx4.bot.utility.ExceptionUtility;
import com.sx4.bot.utility.HelpUtility;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageReaction.ReactionEmote;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;

public class ReactionRole extends Sx4Command {

	private final Predicate<Throwable> defaultReactionFailure = exception -> {
		if (exception instanceof ErrorResponseException) {
			ErrorResponseException errorResponse = ((ErrorResponseException) exception);
			return errorResponse.getErrorCode() == 400 || errorResponse.getErrorResponse() == ErrorResponse.UNKNOWN_EMOJI;
		}
		
		return false;
	};
	
	public ReactionRole() {
		super("reaction role");
		
		super.setDescription("Set up a reaction role so users can simply react to an emote and get a specified role");
		super.setAliases("reactionrole");
		super.setExamples("reaction role add", "reaction role remove");
	}
	
	public void onCommand(CommandEvent event) {
		event.reply(HelpUtility.getHelpMessage(event.getCommand(), event.getSelfMember().hasPermission(event.getTextChannel(), Permission.MESSAGE_EMBED_LINKS))).queue();
	}
	
	@Command(value="add", description="Adds a role to be given when a user reacts to the specified emote")
	@Examples({"reaction role add 643945552865919002 🐝 @Yellow", "reaction role add https://discordapp.com/channels/330399610273136641/678274453158887446/680051429460803622 :doggo: Dog person"})
	@AuthorPermissions({Permission.MANAGE_ROLES})
	@BotPermissions({Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_HISTORY})
	@Cooldown(value=2)
	public void add(Sx4CommandEvent event, @Argument(value="message id") MessageArgument messageArgument, @Argument(value="emote") ReactionEmote emote, @Argument(value="role", endless=true) Role role) {
		if (role.isPublicRole()) {
			event.reply("I cannot give the `@everyone` role :no_entry:").queue();
			return;
		}
		
		if (role.isManaged()) {
			event.reply("I cannot give managed roles :no_entry:").queue();
			return;
		}
		
		if (!event.getSelfMember().canInteract(role)) {
			event.reply("I cannot give a role higher or equal than my top role :no_entry:").queue();
			return;
		}
		
		if (!event.getMember().canInteract(role)) {
			event.reply("You cannot give a role higher or equal than your top role :no_entry:").queue();
			return;
		}
		
		boolean unicode = emote.isEmoji();
		String identifier = unicode ? "name" : "id";
		messageArgument.getRestAction().queue(message -> {
			if (message.getReactions().size() >= 20) {
				event.reply("That message is at the max amount of reactions (20) :no_entry:").queue();
				return;
			}
			
			if (message.getType() != MessageType.DEFAULT) {
				event.reply("You cannot have a reaction role on this message :no_entry:").queue();
				return;
			}
			
			List<Document> reactionRoles = this.database.getGuildById(event.getGuild().getIdLong(), Projections.include("reactionRole.reactionRoles")).getEmbedded(List.of("reactionRole", "reactionRoles"), Collections.emptyList());
			
			Document reactionRole = reactionRoles.stream()
				.filter(data -> data.getLong("id") == message.getIdLong())
				.findFirst()
				.orElse(null);
			
			Document reactionData = new Document("emote", new Document(identifier, unicode ? emote.getEmoji() : emote.getEmote().getIdLong()))
				.append("roles", List.of(role.getIdLong()));
			
			Bson update;
			List<Bson> arrayFilters;
			if (reactionRole == null) {
				Document data = new Document("id", message.getIdLong())
					.append("channelId", message.getChannel().getIdLong())
					.append("reactions", List.of(reactionData));
				
				update = Updates.push("reactionRole.reactionRoles", data);
				arrayFilters = null;
			} else {
				Document reaction = reactionRole.getList("reactions", Document.class).stream()
					.filter(data -> {
						Document emoteData = data.get("emote", Document.class);
						if (unicode) {
							if (emoteData.containsKey("name")) {
								return emoteData.getString("name").equals(emote.getEmoji());
							} 
							
							return false;
						} else {
							return emoteData.get("id", 0L) == emote.getEmote().getIdLong();
						}
					})
					.findFirst()
					.orElse(null);
				
				if (reaction == null) {
					update = Updates.push("reactionRole.reactionRoles.$[reactionRole].reactions", reactionData);
					arrayFilters = List.of(Filters.eq("reactionRole.id", message.getIdLong()));
				} else {
					List<Long> roles = reaction.getList("roles", Long.class);
					if (roles.contains(role.getIdLong())) {
						event.reply("That role is already given when reacting to this reaction :no_entry:").queue();
						return;
					}
					
					if (roles.size() >= 10) {
						event.reply("A single reaction cannot give more than 10 roles :no_entry:").queue();
						return;
					}
					
					update = Updates.addToSet("reactionRole.reactionRoles.$[reactionRole].reactions.$[reaction].roles", role.getIdLong());
					arrayFilters = List.of(Filters.eq("reactionRole.id", message.getIdLong()), Filters.eq("reaction.emote." + identifier, unicode ? emote.getEmoji() : emote.getEmote().getIdLong()));
				}
			}
			
			UpdateOptions options = new UpdateOptions().arrayFilters(arrayFilters).upsert(true);
			if (unicode && message.getReactionByUnicode(emote.getEmoji()) == null) {
				message.addReaction(emote.getEmoji()).queue($ -> {
					this.database.updateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((result, exception) -> {
						if (exception != null) {
							ExceptionUtility.sendExceptionally(event, exception);
							return;
						}
						
						event.reply("The role `" + role.getName() + "` will now be given when reacting to " + emote.getEmoji() + " <:done:403285928233402378>").queue();
					});
				}, new ErrorHandler().handle(this.defaultReactionFailure, exception -> event.reply("I could not find that emote :no_entry:").queue()));
			} else {
				if (!unicode && message.getReactionById(emote.getEmote().getIdLong()) == null) {
					message.addReaction(emote.getEmote()).queue();
				}
				
				this.database.updateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((result, exception) -> {
					if (exception != null) {
						ExceptionUtility.sendExceptionally(event, exception);
						return;
					}
					
					event.reply("The role `" + role.getName() + "` will now be given when reacting to " + (unicode ? emote.getEmoji() : emote.getEmote().getAsMention()) + " <:done:403285928233402378>").queue();
				});
			}
		}, new ErrorHandler().handle(ErrorResponse.UNKNOWN_MESSAGE, exception -> event.reply("I could not find that message :no_entry:").queue()));
	}
	
	@Command(value="remove", description="Removes a role or a whole reaction from the reaction role")
	@Examples({"reaction role remove 643945552865919002 🐝", "reaction role add https://discordapp.com/channels/330399610273136641/678274453158887446/680051429460803622 🐝 @Yellow"})
	@AuthorPermissions({Permission.MANAGE_ROLES})
	@BotPermissions({Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_HISTORY})
	@Cooldown(value=2)
	public void remove(Sx4CommandEvent event, @Argument(value="message id") MessageArgument messageArgument, @Argument(value="emote") ReactionEmote emote, @Argument(value="role", endless=true, nullDefault=true) Role role) {
		boolean unicode = emote.isEmoji();
		String identifier = unicode ? "name" : "id";
		
		Bson update;
		List<Bson> arrayFilters;
		if (role == null) {
			update = Updates.pull("reactionRole.reactionRoles.$[reactionRole].reactions", Filters.eq("emote." + identifier, unicode ? emote.getEmoji() : emote.getEmote().getIdLong()));
			arrayFilters = List.of(Filters.eq("reactionRole.id", messageArgument.getMessageId()));
		} else {
			update = Updates.pull("reactionRole.reactionRoles.$[reactionRole].reactions.$[reaction].roles", role.getIdLong());
			arrayFilters = List.of(Filters.eq("reactionRole.id", messageArgument.getMessageId()), Filters.eq("reaction.emote." + identifier, unicode ? emote.getEmoji() : emote.getEmote().getIdLong()));
		}
		
		FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().arrayFilters(arrayFilters).returnDocument(ReturnDocument.BEFORE).projection(Projections.include("reactionRole.reactionRoles"));
		this.database.findAndUpdateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((data, exception) -> {
			if (exception != null) {
				ExceptionUtility.sendExceptionally(event, exception);
				return;
			}
			
			if (data == null) {
				event.reply("There was no reaction role on that message :no_entry:").queue();
				return;
			}
			
			List<Document> reactionRoles = data.getEmbedded(List.of("reactionRole", "reactionRoles"), Collections.emptyList());

			Document reactionRole = reactionRoles.stream()
				.filter(d -> d.getLong("id") == messageArgument.getMessageId())
				.findFirst()
				.orElse(null);
			
			if (reactionRole == null) {
				event.reply("There was no reaction role on that message :no_entry:").queue();
				return;
			}
			
			Document reaction = reactionRole.getList("reactions", Document.class).stream()
				.filter(d -> {
					Document emoteData = d.get("emote", Document.class);
					if (unicode) {
						if (emoteData.containsKey("name")) {
							return emoteData.getString("name").equals(emote.getEmoji());
						} 
						
						return false;
					} else {
						return emoteData.get("id", 0L) == emote.getEmote().getIdLong();
					}
				})
				.findFirst()
				.orElse(null);
			
			if (reaction == null) {
				event.reply("There was no reaction role for that emote :no_entry:").queue();
				return;
			}
			
			if (role == null) {
				event.reply("The reaction " + (unicode ? emote.getEmoji() : emote.getEmote().getAsMention()) + " will no longer give any roles <:done:403285928233402378>").queue();
			} else {
				if (!reaction.getList("roles", Long.class).contains(role.getIdLong())) {
					event.reply("That role was not linked to that reaction :no_entry:").queue();
					return;
				}
				
				event.reply("The role `" + role.getName() + "` has been removed from that reaction <:done:403285928233402378>").queue();
			}
		});
	}
	
	@Command(value="dm", description="Enables/disables whether a reaction role should send dms when a user acquires roles")
	@Examples({"reaction role dm enable all", "reaction role dm disable all", "reaction role dm enable 643945552865919002", "reaction role dm disable 643945552865919002"})
	@AuthorPermissions({Permission.MANAGE_ROLES})
	@BotPermissions({Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_HISTORY})
	@Cooldown(value=2)
	public void dm(Sx4CommandEvent event, @Argument(value="update type") @ExcludeUpdate(UpdateType.TOGGLE) UpdateType updateType, @Argument(value="message id") All<MessageArgument> allArgument) {
		boolean all = allArgument.isAll(), value = updateType.getValue();
		long messageId = all ? 0L : allArgument.getValue().getMessageId();
		
		Bson update;
		List<Bson> arrayFilters;
		if (all) {
			update = value ? Updates.unset("reactionRole.reactionRoles.$[].dm") : Updates.set("reactionRole.reactionRoles.$[].dm", false);
			arrayFilters = null;
		} else {
			update = value ? Updates.unset("reactionRole.reactionRoles.$[reactionRole].dm") : Updates.set("reactionRole.reactionRoles.$[reactionRole].dm", false);
			arrayFilters = List.of(Filters.eq("reactionRole.id", messageId));
		}
		
		UpdateOptions options = new UpdateOptions().arrayFilters(arrayFilters);
		this.database.updateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((result, exception) -> {
			if (exception != null) {
				ExceptionUtility.sendExceptionally(event, exception);
				return;
			}
			
			if (result.getModifiedCount() == 0 && result.getMatchedCount() != 0) {
				event.reply((all ? "All your reaction roles" : "That reaction role") + " already " + (all ? "have" : "has") + " it set to " + (value ? "" : "not ") + "dm users :no_entry:").queue();
				return;
			}
			
			if (result.getModifiedCount() == 0) {
				event.reply(all ? "You do not have any reaction roles setup :no_entry:" : "There is no reaction role linked to that message :no_entry:").queue();
				return;
			}
			
			event.reply("Reactions roles " + (all ? "" : "for that message ") + "will " + (value ? "now" : "no longer") + " send dms <:done:403285928233402378>").queue();
		});
	}
	
}
