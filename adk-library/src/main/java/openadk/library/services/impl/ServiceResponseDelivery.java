//
// Copyright (c)1998-2011 Pearson Education, Inc. or its affiliate(s). 
// All rights reserved.
//

package openadk.library.services.impl;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.StringTokenizer;

import openadk.library.ADK;
import openadk.library.ADKException;
import openadk.library.SIFParser;
import openadk.library.SIFVersion;
import openadk.library.SIFWriter;
import openadk.library.Zone;
import openadk.library.common.YesNo;
import openadk.library.impl.ADKUtils;
import openadk.library.impl.MessageStreamer;
import openadk.library.impl.SIFIOFormatter;
import openadk.library.impl.ZoneImpl;
import openadk.library.infra.SIF_Ack;
import openadk.library.infra.SIF_Error;
import openadk.library.infra.SIF_Header;
import openadk.library.infra.SIF_ObjectData;
import openadk.library.infra.SIF_Response;
import openadk.library.infra.SIF_ServiceOutput;
import openadk.util.ADKStringUtils;
import openadk.util.GUIDGenerator;


public class ServiceResponseDelivery {
	/**
	 * 	Identifies the generic SIF_Response input directory
	 */
	public static final byte SRC_GENERIC = (byte)0;

	/**
	 * 	Identifies the SIF_ReportObject SIF_Response input directory
	 */
	public static final byte SRC_SIFREPORTOBJECT = (byte)1;

	/**
	 *  Instruct customers to set this flag to false if they want to see the
	 *  output generated by ResponseDelivery in the agent's work/responses
	 *  directory (the packet files won't be deleted when the SIF_Responses are
	 *  sent.)
	 */
	public static boolean DELETE_ON_SUCCESS = true;

	protected String fWorkDir;
	protected SIFParser fParser;
	protected ZoneImpl fZone;
	protected byte fSrc;
	char[] buf = new char[1024];
	
	protected String service = "";
	protected String operation = "";
	protected String serviceMsgId = "";

	/**
	 *  Constructor.<p>
	 *
	 * 	@param zone The zone
	 *
	 * 	@param source Identifies the source of pending SIF_Response packets to
	 *		be processed: <code>RSPTYPE_GENERIC</code> to process files in the default
	 *		'responses' directory, or <code>RSPTYPE_SIFREPORTOBJECT</code> to process
	 *		SIF_ReportObject files in the 'responses/reports' directory
	 */
	public ServiceResponseDelivery( Zone zone, byte source )
		throws ADKException
	{
		fZone = (ZoneImpl)zone;
		fParser = SIFParser.newInstance();
		fSrc = source;
		fWorkDir = getSourceDirectory( source, fZone );
	}

	/**
	 * 	Determines the full path to the source directory.<p>
	 *
	 *	All SIF_Response source directories are located in the agent's work directory.
	 *	Generic SIF_Responses are found in a directory named "{agent-home}/work/{zoneId}_{zoneHost}/responses/".
	 *	SIF_Responses for SIF_ReportObject requests are found in a directory named
	 *	"{agent-home}/work/{zoneId}_{zoneHost}/responses/reports".<p>
	 *
	 * 	@param zone The associated zone
	 * 	@param source Identifies the type of pending SIF_Response packets. This flag may
	 * 		be any <code>SRC_</code> constant defined by this class.
	 *	@return The fully-qualified path to the directory where pending response packets
	 *		are located for the type of responses identified by <i>source</i>
	 */
	public static String getSourceDirectory( byte source, Zone zone )
	{
		StringBuffer workDir = new StringBuffer();

		workDir.append( zone.getAgent().getHomeDir() );
		if( workDir.charAt( workDir.length() - 1 ) != File.separatorChar )
			workDir.append( File.separatorChar );
		workDir.append( "work" );
		workDir.append( File.separator );
		workDir.append( ADKStringUtils.safePathString( zone.getZoneId() + "_" + zone.getZoneUrl().getHost() ) );
		workDir.append( File.separator );
		workDir.append( "responses" );
		if( source == SRC_SIFREPORTOBJECT ) {
			workDir.append( File.separator );
			workDir.append( "reports" );
		}

		return workDir.toString();
	}

	/**
	 * 	Determines if the specified source directory exists and contains one or more files.<p>
	 * 	@param source Identifies the type of pending SIF_Response packets. This flag may
	 * 		be any <code>SRC_</code> constant defined by this class.
	 *	@return <code>true</code> if the source directory exists and contains at least one file
	 */
	public static boolean hasPendingPackets( byte source, Zone zone )
	{
		File f = new File( getSourceDirectory( source, zone ) );
		if( f.exists() ) {
			File[] contents = f.listFiles();
			if( contents != null && contents.length > 0 )
				return true;
		}

		return false;
	}

	/**
	 *  Signal the ResponseDelivery thread that SIF_Response packets are available
	 *  for sending to the zone.
     */
	public synchronized void process()
		throws ADKException
	{
		//  Look for all non-"*.pkt" files; if a file doesn't end in an extension,
		//	it has no content and represents a pending SIF_Response. These are the
		//	files we're interested in processing in the files[] loop below.
		File dir = new File( fWorkDir );
		File[] files = dir.listFiles(
			new FilenameFilter() {
				public boolean accept( File dir, String name ) {
					boolean isCandidate = !name.endsWith(".pkt") && // SIF_Response packet files
						   !name.endsWith(".rpt") && // ReportInfo files for SIF_ReportObject responses
						   !name.startsWith("."); // hidden files on some platforms like Mac OS X
					if( isCandidate ){
						String fullName = dir.getPath() + "\\" + name;
						File candidateFile = new File( fullName );
						isCandidate = !candidateFile.isDirectory();
					}
					return isCandidate;
				}
			}
		);

		if( files != null && files.length > 0 )
		{
			if( ( ( ADK.debug & ADK.DBG_MESSAGING_RESPONSE_PROCESSING ) != 0 ) )
				fZone.log.debug( "Processing " + ( files.length ) + " pending SIF_Response packets..." );

			for( int i = 0; i < files.length; i++ )
			{
				// Todo : parse off the more packet flag
				String fileName = files[i].getName();
				boolean responseHasMorePackets = fileName.endsWith( "Y" );
				final String _msgId = fileName.substring( 0, fileName.length() - 2 );

				//  Get all packets awaiting delivery...
				File[] packets = dir.listFiles(
					new FilenameFilter() {
						public boolean accept( File dir, String name ) {
							return name.startsWith(_msgId) && name.endsWith(".pkt");
						}
					}
				);

				if( packets == null )
					continue;

				if( ( ADK.debug & ADK.DBG_MESSAGING_RESPONSE_PROCESSING ) != 0 )
					fZone.log.debug( "Found " + ( packets.length ) + " pending SIF_Response packets for request " + _msgId );

				//  Sort the files by packet #
				Arrays.sort( packets,

					new Comparator()
					{
						public int compare( Object o1, Object o2 )
						{
							//  Get first file's packet #
							StringTokenizer tok = new StringTokenizer( ((File)o1).getName(),"." );
							tok.nextToken(); tok.nextToken();
							int n1 = Integer.parseInt( tok.nextToken() );

							//  Get second file's packet #
							tok = new StringTokenizer( ((File)o2).getName(),"." );
							tok.nextToken(); tok.nextToken();
							int n2 = Integer.parseInt( tok.nextToken() );

							if( n1 < n2 )
								return -1; else
							if( n1 == n2 )
								return 0;

							return 1;
						}

						public boolean equals( Object obj ) {
							return this.equals(obj);
						}
					}
				);

				//  Process each packet
				int p = 0;
				for( p = 0; p < packets.length; p++ ){
					boolean morePackets = responseHasMorePackets || ( p < ( packets.length - 1 ) ) ;
					sendPacket( packets[p], morePackets  );
				}

				//  Delete files[i] if we processed all packets. If the
				//  thread is being shut down and not all packets were
				//  processed, however, leave the file for the next
				//  session.
				if( p >= packets.length )
				{
//					System.out.println( "Deleting " + files[i].getAbsolutePath() );
					files[i].delete();

					if( fSrc == SRC_SIFREPORTOBJECT )
					{
						//	SRC_SIFREPORTOBJECT: Also delete the .rpt file
						File f = new File( fWorkDir + File.separator + _msgId + ".rpt" );
//						System.out.println( "Deleting " + f.getAbsolutePath() );
						f.delete();
					}
				}
			}
		}
	}

	protected void sendPacket( File file, boolean morePackets )
		throws ADKException
	{
		long extraBytes = 0;

		ResponsePacketInfo responsePacket = deserializeResponseFileName( file.getName() );

		/*	If we're processing SIF_ReportObject responses, read the ReportInfo
		 * 	data from the "requestMsgId.rpt" file
		 *
    	 *  TT 894 - If a SIFException is thrown after the ReportInfo is set on
    	 *  the ReportObjectStream, then we don't want to include that ReportInfo
    	 *  in the packet with the error.  In that case, rptInfoReader will be
    	 *  null and will not be included in the list of payloads below.
    	 */
		BufferedReader rptInfoReader = null;
		if( fSrc == SRC_SIFREPORTOBJECT && !responsePacket.errorPacket )
		{
			try
			{
				File f = new File( fWorkDir + File.separator + responsePacket.destinationId + "." + responsePacket.requestMsgId + ".rpt" );
				rptInfoReader = SIFIOFormatter.createInputReader( new FileInputStream( f ) );
				extraBytes = f.length();
			}
			catch( IOException fnfe )
			{
				fZone.log.debug( "Error sending SIF_ReportObject packet #" + responsePacket.packetNumber + ( morePackets ? "": " (last packet)" ) + ", file not found: " + fnfe.getMessage() );
			}
		}

		if( ( ADK.debug & ADK.DBG_MESSAGING ) != 0 )
   			fZone.log.debug( "Sending " +
   					( responsePacket.errorPacket ? "SIF_Error response" : "SIF_Response" ) +
   					" packet #" + responsePacket.packetNumber +
   					( morePackets ? "" : " (last packet)" ) );

		//  Prepare SIF_Response
		SIF_ServiceOutput rsp = new SIF_ServiceOutput();
		rsp.setSIF_MorePackets( morePackets ? YesNo.YES : YesNo.NO );
		rsp.setSIF_ServiceMsgId(serviceMsgId);
		rsp.setSIF_PacketNumber( responsePacket.packetNumber );
		rsp.setSIF_Service(service);
		rsp.setSIF_Operation(operation);

		//  The SIF_Response is rendered in the same version of SIF as the original SIF_Request

		rsp.setSIFVersion( responsePacket.version );

		if( responsePacket.errorPacket )
		{
			//  Write an empty "<SIF_Error> </SIF_Error>" for the MessageStreamer
			//  to replace
			SIF_Error err = new SIF_Error();
			err.setTextValue( " " );
			rsp.setSIF_Error( err );
		}

		if ( !responsePacket.errorPacket || responsePacket.version.getMajor() == 1  )
		{
			//  Write an empty "<SIF_ObjectData> </SIFObjectData>" for the
			//  MessageStreamer to fill in. If this is an errorPacket, the empty
			//  element is required per the SIF 1.x Specifications, but disallowed
			// in SIF 2.x.
			rsp.setSIF_Body(" ");
		}

		//  Assign values to message header - this is usually done by
		//  MessageDispatcher.send() but because we're preparing a SIF_Response
		//  output stream we need to do it manually
		SIF_Header hdr = rsp.getHeader();
		hdr.setSIF_Timestamp( Calendar.getInstance() );
		hdr.setSIF_MsgId(GUIDGenerator.makeGUID());
		hdr.setSIF_SourceId(fZone.getAgent().getId());
		hdr.setSIF_Security(fZone.getFDispatcher().secureChannel());
		hdr.setSIF_DestinationId( responsePacket.destinationId );

		//  Write SIF_Response -- without its SIF_ObjectData payload -- to a buffer
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		SIFWriter writer = new SIFWriter( SIFIOFormatter.createOutputWriter( bytes ), false );
		writer.write( rsp );
		writer.flush();
		writer.close();

		//  Determine the total number of bytes we'll be sending
		int a = bytes.size();
		long b = file.length();

		try
		{
			//  Send the SIF_Response as a stream
	    	Reader envelope = SIFIOFormatter.createInputReader( new ByteArrayInputStream( bytes.toByteArray() ) );
			BufferedReader fr = SIFIOFormatter.createInputReader( new FileInputStream( file ) );
		    Reader[] payloads = null;
		    if( fSrc == SRC_GENERIC )
		    {
			    payloads = new BufferedReader[] { new BufferedReader(fr) };
		    }
		    else
		    {
		    	if( rptInfoReader != null )
		    		payloads = new BufferedReader[] { rptInfoReader, fr };
		    	else
		    		payloads = new BufferedReader[] { fr };
		    }

			MessageStreamer ms = new MessageStreamer( envelope, payloads,
					responsePacket.errorPacket ? "<SIF_Error>" : "<SIF_Body>" );
			if( responsePacket.errorPacket ){
				ms.setReplaceMode( true );
			}
			if( ( ADK.debug & ADK.DBG_MESSAGING ) != 0 )
				fZone.log.debug("Send SIF_Response");
			if( ( ADK.debug & ADK.DBG_MESSAGING_DETAILED ) != 0 ) {
				fZone.log.debug("  MsgId: " + rsp.getMsgId() );
			}

			String ackStr = fZone.getProtocolHandler().send( ms, a+(int)b+(int)extraBytes );

			//  Parse the results into a SIF_Ack
			SIF_Ack ack = (SIF_Ack)fParser.parse(ackStr,fZone);
			if( ack != null ) {
				ack.LogRecv(fZone.log);
			}

			//  If we get here, the message was sent successfully
			envelope.close();
			for( int i = 0; i < payloads.length; i++ )
				payloads[i].close();
			fr.close();

			if( DELETE_ON_SUCCESS )
			    file.delete();
		}
		catch( ADKException adke )
		{
			ADKUtils._throw( adke, fZone.log );
		}
		catch( Exception e )
		{
			ADKUtils._throw( new ADKException("Failed to send SIF_Response: " + e, fZone ), fZone.log );
		}
	}

	/**
	 * This method is implemented here to be in close proximity to the method that parses a file name to
	 * retrieve these bits of information back out
	 * @param builder
	 * @param destinationId
	 * @param requestMsgId
	 * @param packetNumber
	 * @param renderAsVersion
	 * @param errorPacket
	 */
	static void serializeResponsePacketFileName( StringBuilder builder, String destinationId, String requestMsgId,
			int packetNumber, SIFVersion renderAsVersion, boolean errorPacket ){

		// FORMAT: "{requestSourceId}.{requestMsgId}.{packet#}.{ver}[.$].pkt"

		builder.append( serializeToken(destinationId) );
		builder.append( '.' );
		builder.append( requestMsgId );
		builder.append( '.' );
		builder.append( packetNumber );
		builder.append( '.' );
		builder.append( renderAsVersion.toSymbol() );

		if( errorPacket  ){
			builder.append( ".$" );
		}
		builder.append( ".pkt" );
	}

	/**
	 * Takes a token component and makes sure that there are no periods in the name,
	 * and safely encodes it into a filename
	 * @param destinationId
	 * @return
	 */
	@SuppressWarnings("deprecation")
	private static String serializeToken(String destinationId) {
		destinationId = destinationId.replace( ".", "~~" );
		try {
			destinationId = URLEncoder.encode( destinationId, SIFIOFormatter.CHARSET_UTF8  );
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			destinationId = URLEncoder.encode( destinationId );
		}
		return destinationId;
	}

	@SuppressWarnings("deprecation")
	private static String deserializeToken(String destinationId ) {
		try {
			destinationId = URLDecoder.decode( destinationId, SIFIOFormatter.CHARSET_UTF8 );
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			destinationId = URLDecoder.decode( destinationId );
		}
		destinationId = destinationId.replace( "~~", "." );
		return destinationId;
	}

	static String serializeResponseHeaderFileName( String destinationId, String requestMsgId, boolean morePackets )
	{
		return serializeToken(destinationId) + '.' + requestMsgId + '.' + (morePackets ? 'Y' : 'N');
	}


	private static ResponsePacketInfo deserializeResponseFileName( String shortFileName ){

		ResponsePacketInfo responsePacket = new ResponsePacketInfo();

		//  Get SIF_DestinationId, SIF_RequestMsgId, and SIF_PacketNumber from
		//  the filename
		//	FORMAT: "{requestSourceId}.{requestMsgId}.{packet#}.{ver}.{morePackets}[.$].pkt"

		StringTokenizer tok = new StringTokenizer( shortFileName,".");

		// TODO: Fix serialization of sourceIds into filename
		String destId = tok.nextToken();
		destId = deserializeToken( destId );

		responsePacket.destinationId = destId;
		responsePacket.requestMsgId = tok.nextToken();
		responsePacket.packetNumber = Integer.parseInt( tok.nextToken() );
		String ver = tok.nextToken();
		ver = ver.replace('_','.');
		responsePacket.version = SIFVersion.parse( ver );

		//  A leading $ on the filename indicates this is a response packet
		//  with a SIF_Error
		responsePacket.errorPacket = shortFileName.endsWith("$.pkt");

		return responsePacket;

	}

	private static class ResponsePacketInfo
	{
		public boolean errorPacket = false;
		public String destinationId;
		public String requestMsgId;
		public int packetNumber;
		public SIFVersion version;
	}

	public void setService(String service) {
		this.service = service;
	}

	public void setOperation(String operation) {
		this.operation = operation;
	}

	public void setServiceMsgId(String serviceMsgId) {
		this.serviceMsgId = serviceMsgId;
	}

}
