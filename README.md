# gocd_telegram
GOCD notification plugin for telegram.<br>
In the home directory of the go user (/var/go)<br>
a file is created .telegram_settings <br>
api_token=<br>
chat_id=<br>
panic_chat_id=<br>
gocd_api_url=http://myhost.com:8153/go<br>
gocd_api_token=<br>
(For the panic version) and<br>
api_token=<br>
chat_id=<br>
(For the default version) <br>
<br>
The panic version differs in 2 things<br>
1. views the state of the last instance via API<br>
2. sends a panic message to a separate chat if the build failed, and the previous launch was successful<br>
<br>
This plugin is a fork of this project https://github.com/cinex-ru/gocd-telegram-notifier (since in its current form it did not work on the latest build)<br>
I would like to thank the author for the good code base.<br>
