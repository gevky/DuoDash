package dev.nova.deck;

/** Row sizing for the pinned action grid in fullscreen dashboards. */
final class DashboardGridSizingPolicy {
    private DashboardGridSizingPolicy() { }

    static int availableGridHeight(int viewportHeight,int contentTop,int contentBottom,
                                   int headerHeight,int headerTop,int headerBottom,
                                   int banksHeight,int banksTop,int banksBottom,
                                   int gridTop,int gridBottom,int trailingContentHeight){
        long used=(long)contentTop+contentBottom+headerHeight+headerTop+headerBottom
                +banksHeight+banksTop+banksBottom+gridTop+gridBottom+trailingContentHeight;
        return (int)Math.max(0,Math.min(Integer.MAX_VALUE,(long)viewportHeight-used));
    }

    static int rowHeight(int availableHeight,int visibleRows,int gutter,int minimumRowHeight,
                         int naturalRowHeight,boolean fullscreen){
        if(!fullscreen||visibleRows<3)return Math.max(1,naturalRowHeight);
        int fitted=Math.max(0,availableHeight-2*Math.max(0,gutter))/3;
        return Math.max(Math.max(1,minimumRowHeight),fitted);
    }

    static int gridHeight(int rows,int rowHeight,int gutter){
        if(rows<=0)return 0;
        return rows*Math.max(1,rowHeight)+(rows-1)*Math.max(0,gutter);
    }
}
