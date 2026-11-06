package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class BatteryRuntimeEstimatorTest {
    @Test public void instantaneousCurrentIsFallbackOnlyForUnusableAverage(){
        assertEquals(-500_000,BatteryRuntimeEstimator.chooseDischargeCurrent(-500_000,-900_000));
        assertEquals(-900_000,BatteryRuntimeEstimator.chooseDischargeCurrent(Integer.MIN_VALUE,-900_000));
        assertEquals(-900_000,BatteryRuntimeEstimator.chooseDischargeCurrent(0,-900_000));
        assertEquals(-900_000,BatteryRuntimeEstimator.chooseDischargeCurrent(-10_000,-900_000));
        assertEquals(Integer.MIN_VALUE,BatteryRuntimeEstimator.chooseDischargeCurrent(Integer.MIN_VALUE,50_000));
    }

    @Test public void estimateWaitsForStableDischargeWindowAndUsesRealChargeCounter(){
        BatteryRuntimeEstimator estimator=new BatteryRuntimeEstimator();
        for(int i=0;i<4;i++)assertEquals(-1,estimator.update(true,false,1_500_000,-500_000,i*5_000L));
        assertEquals(180,estimator.update(true,false,1_500_000,-500_000,20_000));
    }

    @Test public void trimsAnInstantaneousDrainSpikeFromSmoothedEstimate(){
        BatteryRuntimeEstimator estimator=new BatteryRuntimeEstimator();int[] currents={-500_000,-500_000,-500_000,-500_000,-8_000_000};
        int result=-1;for(int i=0;i<currents.length;i++)result=estimator.update(true,false,1_500_000,currents[i],i*5_000L);
        assertEquals(180,result);
    }

    @Test public void chargingPowerBypassUnsupportedAndNearZeroDrainNeverInventAnEta(){
        BatteryRuntimeEstimator estimator=new BatteryRuntimeEstimator();
        for(int i=0;i<6;i++)assertEquals(-1,estimator.update(false,false,2_000_000,-500_000,i*5_000L));
        for(int i=0;i<6;i++)assertEquals(-1,estimator.update(true,true,2_000_000,-500_000,40_000+i*5_000L));
        for(int i=0;i<6;i++)assertEquals(-1,estimator.update(true,false,2_000_000,Integer.MIN_VALUE,80_000+i*5_000L));
        for(int i=0;i<6;i++)assertEquals(-1,estimator.update(true,false,2_000_000,-10_000,120_000+i*5_000L));
        for(int i=0;i<4;i++)assertEquals(-1,estimator.update(true,false,2_000_000,-500_000,160_000+i*5_000L));
        assertEquals(240,estimator.update(true,false,2_000_000,-500_000,180_000));
        for(int i=0;i<6;i++)assertEquals(-1,estimator.update(true,false,30_000_001,-500_000,200_000+i*5_000L));
    }
}
