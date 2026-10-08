CREATE PROCEDURE [reporting].[GetAccountEvents]
    @AccountId bigint,
    @EventType nvarchar(40) = N'created'
AS
BEGIN
    SET NOCOUNT ON;
    SELECT [event_id], [event_type]
    FROM [dbo].[account_event]
    WHERE [account_id] = @AccountId AND [event_type] = @EventType;
END
