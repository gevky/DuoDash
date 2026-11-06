package dev.nova.deck;

/** Separates dashboard data availability from authenticated helper health. */
final class BridgeStatusPoll {
    interface Query<T> { T read() throws Exception; }
    static final class Result<T> {
        final T status;
        final boolean helperReady;
        final Exception failure;
        Result(T status,boolean helperReady,Exception failure){this.status=status;this.helperReady=helperReady;this.failure=failure;}
    }
    static <T> Result<T> read(Query<T> status,Query<Boolean> health){
        try{return new Result<>(status.read(),true,null);}
        catch(Exception failure){
            boolean ready=false;
            try{ready=Boolean.TRUE.equals(health.read());}catch(Exception ignored){}
            return new Result<>(null,ready,failure);
        }
    }
    private BridgeStatusPoll(){}
}
