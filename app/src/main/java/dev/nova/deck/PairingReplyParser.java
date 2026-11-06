package dev.nova.deck;

/** Parses the private notification reply: six-digit code, optionally followed by its Settings port. */
final class PairingReplyParser {
    static final class Reply {
        final char[] code;
        final int port;
        final boolean valid;
        final String error;
        private Reply(char[] code,int port,boolean valid,String error){this.code=code;this.port=port;this.valid=valid;this.error=error;}
        void clear(){java.util.Arrays.fill(code,'\0');}
    }

    private PairingReplyParser(){}

    static Reply parse(CharSequence value){
        if(value==null)return invalid("Enter the six-digit code from Wireless debugging.");
        String[] parts=value.toString().trim().split("\\s+");
        if(parts.length==1&&parts[0].matches("[0-9]{6}"))return valid(parts[0],0);
        if(parts.length==2&&parts[0].matches("[0-9]{6}")&&parts[1].matches("[0-9]{1,5}")){
            try{int port=Integer.parseInt(parts[1]);if(port>=1&&port<=65535)return valid(parts[0],port);}catch(NumberFormatException ignored){}
        }
        return invalid("Reply with the six-digit code, or the code followed by the pairing port shown in Wireless debugging.");
    }

    private static Reply valid(String code,int port){return new Reply(code.toCharArray(),port,true,"");}
    private static Reply invalid(String error){return new Reply(new char[0],0,false,error);}
}
