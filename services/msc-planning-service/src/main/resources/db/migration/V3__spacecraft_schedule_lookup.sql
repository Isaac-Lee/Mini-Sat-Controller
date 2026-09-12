CREATE INDEX planning_schedule_spacecraft ON state_head ((body#>>'{key,spacecraftId,value}'))
WHERE kind='mission-schedule';
