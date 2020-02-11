package com.sx4.bot.commands.mod;

import java.time.Duration;

import com.jockie.bot.core.argument.Argument;
import com.jockie.bot.core.command.Context;
import com.jockie.bot.core.command.impl.CommandEvent;
import com.jockie.bot.core.option.Option;
import com.mongodb.client.model.Projections;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.database.Database;
import com.sx4.bot.entities.mod.Reason;
import com.sx4.bot.entities.mod.tempban.TempBanData;
import com.sx4.bot.events.mod.TempBanEvent;
import com.sx4.bot.utility.ExceptionUtility;
import com.sx4.bot.utility.ModUtility;
import com.sx4.bot.utility.SearchUtility;
import com.sx4.bot.utility.TimeUtility;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.requests.ErrorResponse;

public class TempBanCommand extends Sx4Command {

	public TempBanCommand() {
		super("temporary ban");
		
		super.setAliases("temp ban", "tempban", "temporaryban");
		super.setDescription("Ban a user for a set amount of time");
		super.setExamples("temporary ban @Shea#6653 5d", "temporary ban Shea 30m Spamming", "temporary ban 402557516728369153 10d t:tos and Spamming");
		super.setAuthorDiscordPermissions(Permission.BAN_MEMBERS);
		super.setBotDiscordPermissions(Permission.BAN_MEMBERS);
	}
	
	public void onCommand(CommandEvent event, @Context Database database, @Argument(value="user") String userArgument, @Argument(value="time", nullDefault=true) Duration time, @Argument(value="reason", endless=true, nullDefault=true) Reason reason, @Option(value="days", description="Set how many days of messages should be deleted from the user") String days) {
		SearchUtility.getUserRest(event.getGuild(), userArgument, user -> {
			if (user == null) {
				event.reply("I could not find that user :no_entry:").queue();
				return;
			}
			
			if (user.getIdLong() == event.getSelfUser().getIdLong()) {
				event.reply("You cannot ban me, that is illegal :no_entry:").queue();
				return;
			}
			
			Member member = event.getGuild().getMember(user);
			if (member != null) {
				if (member.canInteract(event.getMember())) {
					event.reply("You cannot ban someone higher or equal than your top role :no_entry:").queue();
					return;
				}
				
				if (member.canInteract(event.getSelfMember())) {
					event.reply("I cannot ban someone higher or equal than my top role :no_entry:").queue();
					return;
				}
			}
			
			event.getGuild().retrieveBan(user).queue(ban -> {
				event.reply("That user is already banned :no_entry:").queue();
			}, new ErrorHandler().handle(ErrorResponse.UNKNOWN_BAN, e -> {
				TempBanData data = new TempBanData(database.getGuildById(event.getGuild().getIdLong(), Projections.include("tempBan")).get("tempBan", Database.EMPTY_DOCUMENT));
				
				long seconds = time == null ? data.getDefaultTime() : time.toSeconds();
				
				database.updateGuildById(data.getUpdate(event.getGuild().getIdLong(), member.getIdLong(), seconds)).whenComplete((result, exception) -> {
					if (exception != null) {
						ExceptionUtility.sendExceptionally(event, exception);
					} else {
						int deleteDays = 1;
						if (days != null) {
							try {
								deleteDays = Integer.parseInt(days);
							} catch (NumberFormatException ex) {}
						}
						
						deleteDays = deleteDays < 0 ? 0 : deleteDays > 7 ? 7 : deleteDays;
						
						event.getGuild()
							.ban(user, deleteDays)
							.reason(ModUtility.getAuditReason(reason, event.getAuthor()))
							.queue($ -> {
								event.reply("**" + user.getAsTag() + "** has been temporarily banned for " + TimeUtility.getTimeString(seconds) + " <:done:403285928233402378>:ok_hand:").queue();
								
								this.modManager.onModAction(new TempBanEvent(event.getMember(), user, reason, member != null, seconds));
								
								this.banManager.putBan(event.getGuild().getIdLong(), member.getIdLong(), seconds);
							});
					}
				});
			}));
		});
	}
	
}
