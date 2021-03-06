/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.object.dna.impl;

import com.tc.bytes.TCByteBuffer;
import com.tc.io.TCByteBufferInput;
import com.tc.io.TCByteBufferInput.Mark;
import com.tc.io.TCByteBufferOutput;
import com.tc.io.TCSerializable;
import com.tc.object.EntityID;
import com.tc.object.LogicalOperation;
import com.tc.object.ObjectID;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAEncoding;
import com.tc.object.dna.api.DNAEncodingInternal;
import com.tc.object.dna.api.LiteralAction;
import com.tc.object.dna.api.LogicalAction;
import com.tc.object.dna.api.LogicalChangeID;
import com.tc.object.dna.api.PhysicalAction;
import com.tc.util.Assert;
import com.tc.util.Conversion;

import java.io.IOException;

public class DNAImpl implements DNA, DNACursor, TCSerializable<DNAImpl> {
  private static final LogicalOperation[] LOGICAL_OPERATION_VALUES = LogicalOperation.values();

  private static final DNAEncodingInternal DNA_STORAGE_ENCODING  = new StorageDNAEncodingImpl();

  private final ObjectStringSerializer     serializer;
  private final boolean                    createOutput;

  protected TCByteBufferInput              input;
  protected TCByteBuffer[]                 dataOut;

  private int                              actionCount           = 0;
  private int                              origActionCount;
  private boolean                          isDelta;

  // Header info; parsed on deserializeFrom()
  private EntityID                         id;
  private int                              arrayLength;
  private long                             version;
  private int                              dnaLength;

  // XXX: cleanup type of this field
  private Object                           currentAction;

  private boolean                          wasDeserialized       = false;

  public DNAImpl(ObjectStringSerializer serializer, boolean createOutput) {
    this.serializer = serializer;
    this.createOutput = createOutput;
  }

  @Override
  public DNACursor getCursor() {
    return this;
  }

  @Override
  public boolean next() throws IOException {
    try {
      return next(DNA_STORAGE_ENCODING);
    } catch (final ClassNotFoundException e) {
      // This shouldn't happen when expand is "false"
      throw Assert.failure("Internal error");
    }
  }

  @Override
  public boolean next(DNAEncoding encoding) throws IOException, ClassNotFoundException {
    // yucky cast
    DNAEncodingInternal encodingInternal = (DNAEncodingInternal) encoding;

    final boolean hasNext = this.actionCount > 0;
    if (hasNext) {
      parseNext(encodingInternal);
      this.actionCount--;
    } else {
        if (this.input.available() != 0) {
          throw new IOException(this.input.available() + " bytes remaining (expect " + 0 + ")");
        }
      }
    return hasNext;
  }

  private void parseNext(DNAEncodingInternal encoding) throws IOException, ClassNotFoundException {
    final byte recordType = this.input.readByte();

    switch (recordType) {
      case BaseDNAEncodingImpl.PHYSICAL_ACTION_TYPE:
        parsePhysical(encoding, false);
        return;
      case BaseDNAEncodingImpl.PHYSICAL_ACTION_TYPE_REF_OBJECT:
        parsePhysical(encoding, true);
        return;
      case BaseDNAEncodingImpl.LOGICAL_ACTION_TYPE:
        parseLogical(encoding);
        return;
      case BaseDNAEncodingImpl.ARRAY_ELEMENT_ACTION_TYPE:
        parseArrayElement(encoding);
        return;
      case BaseDNAEncodingImpl.ENTIRE_ARRAY_ACTION_TYPE:
        parseEntireArray(encoding);
        return;
      case BaseDNAEncodingImpl.LITERAL_VALUE_ACTION_TYPE:
        parseLiteralValue(encoding);
        return;
      case BaseDNAEncodingImpl.SUB_ARRAY_ACTION_TYPE:
        parseSubArray(encoding);
        return;
      default:
        throw new IOException("Invalid record type: " + recordType);
    }

    // unreachable
  }

  private void parseSubArray(DNAEncodingInternal encoding) throws IOException, ClassNotFoundException {
    final int startPos = this.input.readInt();
    final Object subArray = encoding.decode(this.input);
    this.currentAction = new PhysicalAction(subArray, startPos);
  }

  private void parseEntireArray(DNAEncodingInternal encoding) throws IOException, ClassNotFoundException {
    final Object array = encoding.decode(this.input);
    this.currentAction = new PhysicalAction(array);
  }

  private void parseLiteralValue(DNAEncodingInternal encoding) throws IOException, ClassNotFoundException {
    final Object value = encoding.decode(this.input);
    this.currentAction = new LiteralAction(value);
  }

  private void parseArrayElement(DNAEncodingInternal encoding) throws IOException, ClassNotFoundException {
    final int index = this.input.readInt();
    final Object value = encoding.decode(this.input);
    this.currentAction = new PhysicalAction(index, value, value instanceof ObjectID);
  }

  private void parsePhysical(DNAEncodingInternal encoding, boolean isReference) throws IOException,
      ClassNotFoundException {
    final String fieldName = this.serializer.readFieldName(this.input);

    final Object value = encoding.decode(this.input);
    this.currentAction = new PhysicalAction(fieldName, value, value instanceof ObjectID || isReference);
  }

  private void parseLogical(DNAEncodingInternal encoding) throws IOException, ClassNotFoundException {
    LogicalChangeID logicalChangeID = LogicalChangeID.NULL_ID;
    if (!input.readBoolean()) {
      logicalChangeID = new LogicalChangeID(input.readLong());
    }
    final LogicalOperation method = LOGICAL_OPERATION_VALUES[this.input.readInt()];
    final int paramCount = this.input.read();
    if (paramCount < 0) { throw new AssertionError("Invalid param count:" + paramCount); }
    final Object[] params = new Object[paramCount];
    for (int i = 0; i < params.length; i++) {
      params[i] = encoding.decode(this.input, serializer);
    }
    this.currentAction = new LogicalAction(method, params, logicalChangeID);
  }

  @Override
  public LogicalAction getLogicalAction() {
    return (LogicalAction) this.currentAction;
  }

  @Override
  public PhysicalAction getPhysicalAction() {
    return (PhysicalAction) this.currentAction;
  }

  @Override
  public Object getAction() {
    return this.currentAction;
  }

  @Override
  public String toString() {
    try {
      final StringBuffer buf = new StringBuffer();
      buf.append("DNAImpl\n");
      buf.append("{\n");
      buf.append("  id->" + getEntityID() + "\n");
      buf.append("  version->" + getVersion() + "\n");
      buf.append("  isDelta->" + isDelta() + "\n");
      buf.append("  actionCount->" + this.actionCount + "\n");
      buf.append("  actionCount (orig)->" + this.origActionCount + "\n");
      buf.append("  deserialized?->" + this.wasDeserialized + "\n");
      buf.append("}\n");
      return buf.toString();
    } catch (final Exception e) {
      return e.getMessage();
    }
  }

  @Override
  public int getArraySize() {
    return this.arrayLength;
  }

  @Override
  public boolean hasLength() {
    return getArraySize() >= 0;
  }

  @Override
  public long getVersion() {
    return this.version;
  }

  /*
   * This methods is synchronized coz both broadcast stage and L2 sync objects stage accesses it simultaneously
   */
  @Override
  public synchronized void serializeTo(TCByteBufferOutput serialOutput) {
    serialOutput.write(this.dataOut);
  }

  @Override
  public DNAImpl deserializeFrom(TCByteBufferInput serialInput) throws IOException {
    this.wasDeserialized = true;

    final Mark mark = serialInput.mark();
    dnaLength = serialInput.readInt();
    if (dnaLength <= 0) { throw new IOException("Invalid length:" + dnaLength); }

    serialInput.tcReset(mark);

    this.input = serialInput.duplicateAndLimit(dnaLength);
    serialInput.skip(dnaLength);

    if (this.createOutput) {
      // this is optional (it's only needed on the server side for txn broadcasts)
      this.dataOut = this.input.toArray();
    }

    // skip over the length
    this.input.readInt();

    this.actionCount = this.input.readInt();
    this.origActionCount = this.actionCount;

    if (this.actionCount < 0) { throw new IOException("Invalid action count:" + this.actionCount); }

    final byte flags = this.input.readByte();

    this.id = EntityID.readFrom(input);

    this.isDelta = Conversion.getFlag(flags, DNA.IS_DELTA);

    if (Conversion.getFlag(flags, DNA.HAS_VERSION)) {
      this.version = this.input.readLong();
    } else {
      this.version = DNA.NULL_VERSION;
    }

    if (Conversion.getFlag(flags, DNA.HAS_ARRAY_LENGTH)) {
      this.arrayLength = this.input.readInt();
    } else {
      this.arrayLength = DNA.NULL_ARRAY_SIZE;
    }

    return this;
  }

  @Override
  public int getActionCount() {
    return this.actionCount;
  }

  @Override
  public boolean isDelta() {
    return this.isDelta;
  }

  @Override
  public EntityID getEntityID() {
    return id;
  }

  @Override
  public void reset() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Reset is not supported by this class");
  }

}
