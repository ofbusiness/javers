package org.javers.core.commit;

import java.util.Random;

/**
 * Generates unique and monotonically increasing commit identifiers. <br>
 * Thread safe. Should not be used in distributed applications.
 *
 * @see DistributedCommitSeqGenerator
 * @author bartosz walacik
 */
class CommitSeqGenerator {
    private HandedOutIds handedOut = new HandedOutIds();
    private Integer randomizer = new Random().nextInt(9);


    synchronized CommitId nextId(CommitId head)
    {
        Long major = getHeadMajorId(head) + 1;

        CommitId lastReturned = handedOut.get(major);

        CommitId result;
        if (lastReturned == null){
            result = new CommitId(major,0);
        }
        else {
            int serverInitMinorKey = lastReturned.getMinorId() < 10 ?
                lastReturned.getMinorId() + randomizer * 10 :
                lastReturned.getMinorId();
            result = new CommitId(major, serverInitMinorKey + 1);
        }

        handedOut.put(result);
        return result;
    }

    long getHeadMajorId(CommitId head){
        if (head == null){
            return 0;
        }
        return head.getMajorId();
    }
}

