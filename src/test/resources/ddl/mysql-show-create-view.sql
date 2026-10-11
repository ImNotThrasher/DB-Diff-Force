CREATE ALGORITHM=UNDEFINED DEFINER=`report_reader`@`%` SQL SECURITY DEFINER VIEW `active_account_events` AS
select `e`.`event_id` AS `event_id`,`e`.`account_id` AS `account_id`,`e`.`event_type` AS `event_type`
from `account_event` `e` where (`e`.`event_type` <> 'deleted')
