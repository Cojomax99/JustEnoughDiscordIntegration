# JustEnoughDiscordIntegration

Connect your Minecraft chat with your Discord chat!

## Description

Install this mod on your Minecraft server (no need to install on clients) and you can connect your Minecraft server chat with any number of channels within Discord guilds you are a part of! The chat goes both ways, Discord to Minecraft and vice versa.

Certain features are built in! These will be posted to Discord whenever the event occurs in game:

## Features:
* Advancements made
* Player deaths
* Named entity deaths
* Player login / logout
* Server starting / started / stopped alerts

## Setup

### Discord Setup
1. Create a Discord bot [here](https://discord.com/developers/applications/ "here"). Click on "New Application", give it the settings you want.

On the left side of that page, click "Bot" and then "Add Bot". Give it a name, pretty picture, and make sure to give it "Server Members Intent" and "Message Content Intent", along with any other settings you want your bot to have.

2. Join the Discord bot to your guild
   On the left, click "OAuth2" then "Url Generator". Under "scopes" select "Bot" and in "Bot Permissions" you should give it at least "Send Messages" (you can always fix the permissions later, though).

As long as you are an admin of the guild you are trying to join to, you can then copy the Generated Url at the bottom of the page and go to that URL in your web browser to join the bot to your guild.

3. Enable "Developer Mode" in Discord

You can find this setting by going to Settings > Appearance > Developer Mode -> ensure it is enabled.

4. Create a webhook per output channel in Discord

For every channel you want to have the Minecraft chat output to, do the following and set aside the URLs for the next section:

- Hover over the channel in Discord and click the cogwheel "Edit Channel"
- Click "Integrations"
- Click "Create Webhook" (or "View Webhooks" if one already exists, then "New Webhook")
- Fill in a name, image, and which channel you want it in.
- Click "Copy webhook URL" and set it aside for later.

### Minecraft Setup

Install the mod on your Forge server. Run the game at least once to generate the config file, then close the game.

Open the config file.

This will be located at: `<worldfolder>/serverconfig/jedi-server.toml`.

This file has 3 important settings:

* `bot_token` - You can find this on the Bot page from Step 1 above. It will be next to the image of your bot. You can click the "copy" button to copy it to your clipboard, and then paste it in the quotes provided for this item. Ex: `= "OTI3MTU4MDY0NjY1MjE0OTk4.YdGJPA.SJTj5Qczbcbno2d7o8ci0fjSts4"`
* `webhook_urls` - Remember the webhook URLs we saved from earlier? Get those now, and paste them in like follows: ` = ["url1", "url2", "url3"]`
* `read_channels` - These are the channel IDs that the bot sends from Discord -> Minecraft. You can right click on a channel and click "Copy ID" to get the ID of a channel. You can have as many channels as you want here, just make sure your bot has permission to read from them. Ex: ` = [12232423423, 93792672323328]`

That's it! Happy chatting!
